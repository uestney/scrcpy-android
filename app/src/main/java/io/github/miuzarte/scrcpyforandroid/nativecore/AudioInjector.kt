package io.github.miuzarte.scrcpyforandroid.nativecore

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AudioInjector v3（按需录音）
 *
 * 协议：
 *   1. 连接 → 待命（不开麦克风）
 *   2. 收到 server 发来的 STRT → 初始化麦克风/Opus 编码器 → 发 OPUS 握手 → 进入采集循环
 *   3. 收到 STOP（或采集出错）→ 释放麦克风和编码器 → 回到待命
 *   4. 一直循环直到 stop() 被调用或 socket 断开
 *
 * 后台 control reader 线程持续读 4 字节命令，主线程根据 sessionWanted 状态切换。
 */
class AudioInjector(private val onLog: ((String) -> Unit)? = null) {

    companion object {
        private const val TAG          = "AudioInjector"
        private const val PORT         = 59152
        private const val SAMPLE_RATE  = 16000
        private const val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FMT    = AudioFormat.ENCODING_PCM_16BIT
        private const val OPUS_BITRATE = 24000
        private const val FRAME_SIZE   = SAMPLE_RATE / 50  // 320 samples = 20ms

        private const val OPUS_MAGIC = 0x4F505553   // "OPUS"
        private const val CMD_STRT   = 0x53545254   // "STRT"
        private const val CMD_STOP   = 0x53544F50   // "STOP"
    }

    private var socket:      Socket?           = null
    private var audioRecord: AudioRecord?      = null
    private var encoder:     MediaCodec?       = null

    @Volatile private var running       = false   // 外部生命周期（stop() 之前一直 true）
    @Volatile private var capturing     = false   // 当前 TCP 连接是否活动
    @Volatile private var sessionWanted = false   // server 是否要求采集中

    // ---------- public API ----------

    fun start(deviceHost: String) {
        if (running) return
        running = true

        Thread({
            // 重连循环：socket 断了就重连，直到 stop() 把 running 置 false
            var attempt = 0
            while (running) {
                attempt++
                val connected = runOneConnection(deviceHost, attempt)
                if (!running) break
                if (!connected) {
                    // 连不上 — 退避后重试
                    val backoffMs = (1000L * Math.min(attempt, 5))   // 1s, 2s, 3s, 4s, 5s 封顶
                    onLog?.invoke("Audio IN: 连接失败，${backoffMs}ms 后重连")
                    try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                } else {
                    // 之前连上过又断了 — 短暂等待后立即重连
                    onLog?.invoke("Audio IN: 连接中断，重连中…")
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
            }
            onLog?.invoke("Audio IN: 已停止")
        }, "AudioInjector").start()
    }

    /** 单次 TCP 连接的完整生命：返回 true 表示曾连上过，false 表示连接都没建起来 */
    private fun runOneConnection(deviceHost: String, attempt: Int): Boolean {
        var input:  DataInputStream?  = null
        var output: DataOutputStream? = null
        var sock:   Socket?           = null
        var connected = false
        try {
            onLog?.invoke("Audio IN: 连接 $deviceHost:$PORT (#$attempt) …")
            sock = Socket()
            sock.connect(InetSocketAddress(deviceHost, PORT), 5000)
            sock.tcpNoDelay = true
            // 注意：不要手动设置 sendBufferSize（公网会出问题）
            input  = DataInputStream(sock.getInputStream())
            output = DataOutputStream(sock.getOutputStream())
            socket    = sock
            connected = true
            Log.i(TAG, "TCP connected: $deviceHost:$PORT")
            onLog?.invoke("Audio IN: 已连接，待命（等 redroid 应用请求录音）")

            capturing     = true
            sessionWanted = false

            startControlReader(input)

            while (capturing && running) {
                while (capturing && running && !sessionWanted) {
                    Thread.sleep(50)
                }
                if (!capturing || !running) break

                runOneSession(output)
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection error: ${e.message}")
            if (!connected) onLog?.invoke("Audio IN: 连接错误 - ${e.message}")
        } finally {
            capturing     = false
            sessionWanted = false
            try { audioRecord?.stop() }    catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            try { encoder?.stop() }    catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            encoder = null
            try { sock?.close() } catch (_: Exception) {}
            socket = null
        }
        return connected
    }

    fun stop() {
        running       = false
        capturing     = false
        sessionWanted = false
        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { encoder?.stop() }    catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // ---------- 控制通道读线程 ----------

    private fun startControlReader(input: DataInputStream) {
        Thread({
            try {
                while (capturing) {
                    val cmd = input.readInt()
                    when (cmd) {
                        CMD_STRT -> {
                            Log.i(TAG, "[CTRL] STRT received")
                            onLog?.invoke("Audio IN: redroid 请求录音，开始采集")
                            sessionWanted = true
                        }
                        CMD_STOP -> {
                            Log.i(TAG, "[CTRL] STOP received")
                            onLog?.invoke("Audio IN: redroid 停止录音，待命")
                            sessionWanted = false
                        }
                        else -> Log.w(TAG, "[CTRL] unknown cmd: 0x${cmd.toString(16)}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[CTRL] reader exit: ${e.message}")
                capturing     = false
                sessionWanted = false
            }
        }, "AudioInjector-ctrl").start()
    }

    // ---------- 一次录音会话 ----------

    private fun runOneSession(out: DataOutputStream) {
        var rec: AudioRecord? = null
        var enc: MediaCodec?  = null
        try {
            // 1. 麦克风
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT)
            if (minBuf <= 0) {
                Log.e(TAG, "getMinBufferSize=$minBuf")
                return
            }
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT,
                Math.max(minBuf, 8192)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed: state=${rec.state}")
                return
            }
            audioRecord = rec

            // 2. Opus 编码器 + 提取 CSD
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoder = enc
            Log.i(TAG, "Opus encoder started ($OPUS_BITRATE bps)")

            val csdList = extractCsd(enc)
            if (csdList.isEmpty()) {
                Log.e(TAG, "CSD extraction failed")
                return
            }
            Log.i(TAG, "CSD: ${csdList.size} entries, sizes=${csdList.map { it.size }}")

            // 3. 发握手
            out.writeInt(OPUS_MAGIC)
            out.writeInt(csdList.size)
            for (csd in csdList) {
                out.writeInt(csd.size)
                out.write(csd)
            }
            out.flush()

            // 4. 开始采集
            rec.startRecording()
            captureLoop(rec, enc, out)
        } catch (e: Exception) {
            Log.e(TAG, "session error: ${e.message}", e)
        } finally {
            try { rec?.stop() }    catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            try { enc?.stop() }    catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            audioRecord = null
            encoder     = null
            Log.i(TAG, "session cleanup done")
        }
    }

    private fun captureLoop(rec: AudioRecord, enc: MediaCodec, out: DataOutputStream) {
        val pcmBuf  = ShortArray(FRAME_SIZE)
        val byteBuf = ByteArray(FRAME_SIZE * 2)
        val info    = MediaCodec.BufferInfo()
        var frameCount = 0

        while (capturing && sessionWanted) {
            val n = rec.read(pcmBuf, 0, FRAME_SIZE)
            if (n <= 0) {
                Log.w(TAG, "[Opus] read returned $n, exit session")
                break
            }

            // 仅作日志：当前帧最大振幅
            val amp = (0 until n).maxOfOrNull { kotlin.math.abs(pcmBuf[it].toInt()) } ?: 0

            val len = n * 2
            for (i in 0 until n) {
                byteBuf[i * 2]     = (pcmBuf[i].toInt() and 0xFF).toByte()
                byteBuf[i * 2 + 1] = (pcmBuf[i].toInt() shr 8).toByte()
            }

            val inIdx = enc.dequeueInputBuffer(10000)
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx)
                inBuf!!.clear()
                inBuf.put(byteBuf, 0, len)
                enc.queueInputBuffer(inIdx, 0, len, frameCount * 20000L, 0)
            }

            while (capturing && sessionWanted) {
                val outIdx = enc.dequeueOutputBuffer(info, 0)
                if (outIdx < 0) break

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    enc.releaseOutputBuffer(outIdx, false)
                    continue
                }

                val encBuf = enc.getOutputBuffer(outIdx)!!
                val data = ByteArray(info.size)
                encBuf.position(info.offset)
                encBuf.get(data)

                try {
                    out.writeInt(info.size)
                    out.write(data)
                    out.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "[Opus] send error: ${e.message}")
                    capturing = false
                    enc.releaseOutputBuffer(outIdx, false)
                    return
                }

                enc.releaseOutputBuffer(outIdx, false)
                frameCount++
                if (frameCount % 50 == 0) {
                    Log.i(TAG, "[Opus] #$frameCount (${info.size}B) amp=$amp")
                    onLog?.invoke("Audio IN: 已发送 $frameCount 帧 (amp=$amp)")
                }
            }
        }
        Log.i(TAG, "[Opus] session ended, frames=$frameCount")
    }

    private fun extractCsd(enc: MediaCodec): List<ByteArray> {
        val result  = mutableListOf<ByteArray>()
        val silence = ByteArray(FRAME_SIZE * 2)
        val inIdx   = enc.dequeueInputBuffer(100000)
        if (inIdx >= 0) {
            val buf = enc.getInputBuffer(inIdx)
            buf!!.clear()
            buf.put(silence)
            enc.queueInputBuffer(inIdx, 0, silence.size, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        var gotFormat = false
        val deadline  = System.nanoTime() + 1_000_000_000L  // 1s

        while (System.nanoTime() < deadline && result.isEmpty()) {
            val idx = enc.dequeueOutputBuffer(info, 10000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = enc.outputFormat
                    for (i in 0..2) {
                        val key = "csd-$i"
                        if (fmt.containsKey(key)) {
                            val buf = fmt.getByteBuffer(key)
                            if (buf != null) {
                                val data = ByteArray(buf.remaining())
                                buf.get(data)
                                result.add(data)
                            }
                        }
                    }
                    gotFormat = true
                }
                idx >= 0 -> {
                    enc.releaseOutputBuffer(idx, false)
                    if (gotFormat) break
                }
            }
        }
        return result
    }
}
