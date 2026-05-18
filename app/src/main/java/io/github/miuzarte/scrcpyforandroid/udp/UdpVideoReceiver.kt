package io.github.miuzarte.scrcpyforandroid.udp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * UDP/RTP H.264 视频接收器（配合 server 端 scrcpy_rtp_sidecar.py）。
 *
 * 设计为「纯包生产者」：
 *  - 不持有 MediaCodec / Surface
 *  - RFC 6184 单 NALU + FU-A 重组
 *  - 每完成一个完整 access unit（关键帧 IDR / 非关键帧 NonIDR）就触发 onPacket 回调
 *  - SPS/PPS 缓存：IDR 前面自动前置 [SC]SPS[SC]PPS[SC]IDR，配合上层 AnnexBDecoder 使用
 *  - 心跳：每收到一个 UDP datagram 就触发 onPacketReceived，让 Scrcpy.kt 更新 lastVideoPacketTime
 */
class UdpVideoReceiver(
    private val videoPort: Int,
    private val onPacket: (data: ByteArray, ptsUs: Long, isKey: Boolean, isConfig: Boolean) -> Unit,
    private val onPacketReceived: (() -> Unit)? = null,
    private val onIdrLost: (() -> Unit)? = null,
    /** 服务端 IP（用于 HELLO 自动地址发现）。null/空 则不发 HELLO（兼容老用法）。 */
    private val serverHost: String? = null,
    /** 服务端 HELLO 监听端口（默认 59155，需与 sidecar --hello-port 一致）。 */
    private val helloPort: Int = 59155,
    /** 每 N 秒发一次 HELLO 心跳。 */
    private val helloIntervalMs: Long = 2000L,
    /** 状态推送（推到 App 文本框）。 */
    private val onStatus: ((String) -> Unit)? = null,
) {
    private var socket: DatagramSocket? = null
    @Volatile private var isRunning = false

    private var spsCache: ByteArray? = null
    private var ppsCache: ByteArray? = null
    @Volatile private var sentConfig = false

    private val fuBuffer    = ByteArrayOutputStream(MAX_NALU_SIZE)
    private var fuNalHeader = 0
    private var fuActive    = false

    private var lastSeq = -1

    private var helloThread: Thread? = null
    @Volatile private var totalPackets = 0L
    @Volatile private var totalBytes   = 0L
    @Volatile private var helloSent    = 0L

    companion object {
        private const val TAG               = "UdpVideoReceiver"
        private const val RTP_HEADER_SIZE   = 12
        private const val PAYLOAD_TYPE_H264 = 96
        private const val MAX_PACKET_SIZE   = 1600
        private const val MAX_NALU_SIZE     = 512 * 1024
        private val START_CODE              = byteArrayOf(0, 0, 0, 1)

        private const val NAL_TYPE_NON_IDR  = 1
        private const val NAL_TYPE_IDR      = 5
        private const val NAL_TYPE_SEI      = 6
        private const val NAL_TYPE_SPS      = 7
        private const val NAL_TYPE_PPS      = 8
        private const val NAL_TYPE_FU_A     = 28
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) { Log.w(TAG, "already running"); return@withContext }
        socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(videoPort))
            soTimeout    = 2000
            receiveBufferSize = 1 shl 20
        }
        Log.i(TAG, "UDP bound to port $videoPort")
        status("UDP video: bound 0.0.0.0:$videoPort, waiting for RTP ...")
        isRunning = true
        Thread({ receiveLoop() }, "UdpVideoReceiver-recv").start()
        startHelloLoop()
    }

    fun stop() {
        Log.i(TAG, "stopping")
        isRunning = false
        helloThread?.interrupt()
        helloThread = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
    }

    private fun status(msg: String) {
        Log.i(TAG, msg)
        onStatus?.invoke(msg)
    }

    /**
     * 周期发 HELLO 给 sidecar 自动地址发现服务。
     * 用同一个 DatagramSocket 既收 RTP 又发 HELLO（5-tuple 友好，NAT 后也能穿）。
     * 注意：DatagramSocket 是线程安全的（send 内部加锁），但 receive 是阻塞的，
     * 这里只在 send 时短暂持有；不影响 receiveLoop。
     */
    private fun startHelloLoop() {
        val host = serverHost?.takeIf { it.isNotBlank() }
        if (host == null) {
            status("UDP video: serverHost is null/blank, HELLO disabled (manual --dst-host mode)")
            return
        }
        val target = try {
            java.net.InetSocketAddress(java.net.InetAddress.getByName(host), helloPort)
        } catch (e: Throwable) {
            status("UDP video: bad serverHost=$host err=${e.message}")
            return
        }
        status("UDP video: HELLO loop -> $host:$helloPort every ${helloIntervalMs}ms")
        val payload = "HELLO\n".toByteArray()
        helloThread = Thread({
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val sock = socket
                if (sock != null && !sock.isClosed) {
                    try {
                        sock.send(DatagramPacket(payload, payload.size, target))
                        helloSent += 1
                        if (helloSent == 1L || helloSent % 30L == 0L) {
                            status("UDP video: HELLO sent=$helloSent, rtpRecv=$totalPackets")
                        }
                    } catch (e: Throwable) {
                        if (isRunning) Log.w(TAG, "HELLO send fail: ${e.message}")
                    }
                }
                try { Thread.sleep(helloIntervalMs) } catch (_: InterruptedException) { break }
            }
            Log.i(TAG, "HELLO loop exit (sent=$helloSent)")
        }, "UdpVideoReceiver-hello").apply { isDaemon = true; start() }
    }

    private fun receiveLoop() {
        val sock = socket ?: return
        val buf  = ByteArray(MAX_PACKET_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)
        Log.i(TAG, "receive loop started")
        while (isRunning) {
            try {
                sock.receive(pkt)
                totalPackets += 1
                totalBytes   += pkt.length
                onPacketReceived?.invoke()
                // 前几个包都打到文本框，方便确认 UDP 通了
                if (totalPackets <= 3L) {
                    status("UDP video: RTP #$totalPackets from ${pkt.address.hostAddress}:${pkt.port} len=${pkt.length}")
                } else if (totalPackets % 300L == 0L) {
                    status("UDP video: rtpRecv=$totalPackets bytes=$totalBytes")
                }
                handleRtp(pkt.data, pkt.length)
            } catch (_: java.net.SocketTimeoutException) {
                // 正常：让上层心跳检测可以触发 idle 判断
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "recv err: ${e.message}")
            }
        }
        Log.i(TAG, "receive loop exit")
    }

    private fun handleRtp(data: ByteArray, len: Int) {
        if (len < RTP_HEADER_SIZE) return
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        if ((b0 shr 6) and 0x3 != 2) return
        val cc     = b0 and 0x0F
        val ext    = (b0 shr 4) and 0x1
        val marker = (b1 shr 7) and 0x1 == 1
        val pt     = b1 and 0x7F
        if (pt != PAYLOAD_TYPE_H264) return
        val seq = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val ts  = ((data[4].toLong() and 0xFF) shl 24) or
                  ((data[5].toLong() and 0xFF) shl 16) or
                  ((data[6].toLong() and 0xFF) shl 8) or
                   (data[7].toLong() and 0xFF)

        var off = RTP_HEADER_SIZE + cc * 4
        if (ext == 1 && len >= off + 4) {
            val extLen = ((data[off + 2].toInt() and 0xFF) shl 8) or
                          (data[off + 3].toInt() and 0xFF)
            off += 4 + extLen * 4
        }
        if (off >= len) return

        trackSeq(seq)

        val nalHeader = data[off].toInt() and 0xFF
        val nalType   = nalHeader and 0x1F
        // RTP ts 90kHz → us
        val ptsUs = ts * 100L / 9L
        when (nalType) {
            in 1..23      -> handleSingleNalu(data, off, len - off, ptsUs, marker)
            NAL_TYPE_FU_A -> handleFuA(data, off, len - off, ptsUs, marker)
            else          -> Log.v(TAG, "skip nal_type=$nalType")
        }
    }

    private fun trackSeq(seq: Int) {
        if (lastSeq < 0) { lastSeq = seq; return }
        val expected = (lastSeq + 1) and 0xFFFF
        if (seq != expected) {
            val diff = ((seq - expected) and 0xFFFF)
            if (diff in 1..1000) {
                Log.w(TAG, "seq gap: expected=$expected got=$seq missing=$diff")
                if (fuActive) { fuBuffer.reset(); fuActive = false }
                onIdrLost?.invoke()
            }
        }
        lastSeq = seq
    }

    private fun handleFuA(buf: ByteArray, off: Int, len: Int, ptsUs: Long, marker: Boolean) {
        if (len < 2) return
        val fuIndicator = buf[off].toInt() and 0xFF
        val fuHeader    = buf[off + 1].toInt() and 0xFF
        val start       = (fuHeader and 0x80) != 0
        val end         = (fuHeader and 0x40) != 0
        val nalType     = fuHeader and 0x1F
        val nri         = fuIndicator and 0x60
        val f           = fuIndicator and 0x80
        val payloadOff  = off + 2
        val payloadLen  = len - 2
        if (start) {
            fuBuffer.reset()
            fuNalHeader = f or nri or nalType
            fuBuffer.write(fuNalHeader)
            fuActive = true
        }
        if (!fuActive) return
        fuBuffer.write(buf, payloadOff, payloadLen)
        if (end) {
            val nalu = fuBuffer.toByteArray()
            fuBuffer.reset(); fuActive = false
            emit(nalu, 0, nalu.size, ptsUs, marker)
        }
    }

    private fun handleSingleNalu(buf: ByteArray, off: Int, len: Int, ptsUs: Long, marker: Boolean) {
        emit(buf, off, len, ptsUs, marker)
    }

    /** 把一个 NALU 转发为 Annex-B 包给上层解码器。 */
    private fun emit(buf: ByteArray, off: Int, len: Int, ptsUs: Long, @Suppress("UNUSED_PARAMETER") marker: Boolean) {
        if (len <= 0) return
        val nalType = buf[off].toInt() and 0x1F
        when (nalType) {
            NAL_TYPE_SPS -> {
                spsCache = buf.copyOfRange(off, off + len)
                emitConfigIfReady()
            }
            NAL_TYPE_PPS -> {
                ppsCache = buf.copyOfRange(off, off + len)
                emitConfigIfReady()
            }
            NAL_TYPE_IDR -> {
                val sps = spsCache; val pps = ppsCache
                if (sps == null || pps == null) {
                    Log.w(TAG, "IDR before SPS/PPS — drop"); onIdrLost?.invoke(); return
                }
                // 每个 IDR 前都重新 emit CONFIG，确保上层 latestConfigPacket 保持新鲜
                // （surface 切换时 decoder 被销毁重建，bootstrap cache 必须有最近的 CONFIG）
                val cfgTotal = START_CODE.size * 2 + sps.size + pps.size
                val cfgOut   = ByteArray(cfgTotal)
                var cp = 0
                System.arraycopy(START_CODE, 0, cfgOut, cp, 4); cp += 4
                System.arraycopy(sps,        0, cfgOut, cp, sps.size); cp += sps.size
                System.arraycopy(START_CODE, 0, cfgOut, cp, 4); cp += 4
                System.arraycopy(pps,        0, cfgOut, cp, pps.size)
                onPacket(cfgOut, /*ptsUs=*/0L, /*isKey=*/false, /*isConfig=*/true)

                // 一次性把 SPS+PPS+IDR 打包成 Annex-B，让解码器一次配置 + 关键帧到位
                val total = START_CODE.size * 3 + sps.size + pps.size + len
                val out = ByteArray(total)
                var p = 0
                System.arraycopy(START_CODE, 0, out, p, 4); p += 4
                System.arraycopy(sps,        0, out, p, sps.size); p += sps.size
                System.arraycopy(START_CODE, 0, out, p, 4); p += 4
                System.arraycopy(pps,        0, out, p, pps.size); p += pps.size
                System.arraycopy(START_CODE, 0, out, p, 4); p += 4
                System.arraycopy(buf,      off, out, p, len)
                onPacket(out, ptsUs, /*isKey=*/true, /*isConfig=*/false)
            }
            else -> {
                val out = ByteArray(START_CODE.size + len)
                System.arraycopy(START_CODE, 0, out, 0, 4)
                System.arraycopy(buf, off, out, 4, len)
                onPacket(out, ptsUs, /*isKey=*/false, /*isConfig=*/false)
            }
        }
    }

    /** SPS+PPS 凑齐后发一次 codec config，避免 AnnexBDecoder 长时间无 csd。 */
    private fun emitConfigIfReady() {
        if (sentConfig) return
        val sps = spsCache; val pps = ppsCache
        if (sps == null || pps == null) return
        val total = START_CODE.size * 2 + sps.size + pps.size
        val out = ByteArray(total)
        var p = 0
        System.arraycopy(START_CODE, 0, out, p, 4); p += 4
        System.arraycopy(sps,        0, out, p, sps.size); p += sps.size
        System.arraycopy(START_CODE, 0, out, p, 4); p += 4
        System.arraycopy(pps,        0, out, p, pps.size)
        onPacket(out, /*ptsUs=*/0L, /*isKey=*/false, /*isConfig=*/true)
        sentConfig = true
        Log.i(TAG, "SPS+PPS config emitted")
    }
}
