package io.github.miuzarte.scrcpyforandroid.udp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * UDP 视频接收器：接收 RTP/UDP H.264 数据包并解码
 *
 * 工作流程：
 * 1. 绑定 UDP 端口接收 RTP 数据包
 * 2. 解析 RTP 头，提取 H.264 NALU
 * 3. 送入 MediaCodec 解码
 * 4. 通过 Surface 渲染
 */
class UdpVideoReceiver(
    private val serverIp: String,
    private val videoPort: Int = 59154,  // 默认高位端口
    private val width: Int = 1280,
    private val height: Int = 720,
) {
    private var socket: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private var surface: android.view.Surface? = null
    private var isRunning = false
    private var surfaceReady = false  // Surface 是否已设置

    companion object {
        private const val TAG = "UdpVideoReceiver"
        private const val RTP_HEADER_SIZE = 12
        private const val PAYLOAD_TYPE_H264 = 96

        // RTP buffer size (1500 MTU - IP header - UDP header - RTP header)
        private const val MAX_PACKET_SIZE = 1472
    }

    /**
     * 设置渲染 Surface
     */
    fun setSurface(surface: android.view.Surface) {
        this.surface = surface
        this.surfaceReady = true
        Log.i(TAG, "Surface set, ready to configure decoder")
        // 如果解码器已创建但未配置，重新配置
        decoder?.let {
            if (it.outputFormat == null) {
                try {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    it.configure(format, surface, null, 0)
                    it.start()
                    Log.i(TAG, "Decoder configured with Surface")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to configure decoder with Surface", e)
                }
            }
        }
    }

    /**
     * 启动接收器
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return@withContext
        }

        try {
            // 创建 UDP socket
            socket = DatagramSocket(null)
            socket?.reuseAddress = true
            socket?.bind(java.net.InetSocketAddress(videoPort))
            socket?.soTimeout = 5000 // 5 秒超时
            Log.i(TAG, "UDP socket bound to port $videoPort")

            // 等待 Surface 设置（最多等待 3 秒）
            var waited = 0
            while (!surfaceReady && waited < 30) {
                Thread.sleep(100)
                waited++
            }

            if (!surfaceReady) {
                Log.w(TAG, "Surface not ready after 3 seconds, decoder will not work")
            }

            // 创建 H.264 解码器
            createDecoder()

            isRunning = true

            // 启动接收线程
            Thread { receiveLoop() }.start()

            Log.i(TAG, "UdpVideoReceiver started (surfaceReady=$surfaceReady)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            stop()
            throw e
        }
    }

    /**
     * 停止接收器
     */
    fun stop() {
        Log.i(TAG, "Stopping UdpVideoReceiver")
        isRunning = false

        decoder?.stop()
        decoder?.release()
        decoder = null

        socket?.close()
        socket = null

        surface?.release()
        surface = null
    }

    /**
     * 创建 H.264 解码器
     */
    private fun createDecoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000) // 4 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)

        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        if (surfaceReady && surface != null && surface!!.isValid) {
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
            Log.i(TAG, "H.264 decoder created and configured with Surface")
        } else {
            // Surface 未就绪，创建解码器但不配置（等待 Surface 设置后配置）
            Log.w(TAG, "H.264 decoder created but NOT configured (Surface not ready)")
        }
    }

    /**
     * 接收循环
     */
    private fun receiveLoop() {
        val sock = socket ?: return
        val dec = decoder ?: return
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        // 用于收集 NALU 的缓冲区
        val naluBuffer = ByteArrayOutputStream()
        val outputBuffer = ByteBuffer.allocate(width * height * 3 / 2)
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            try {
                // 接收 UDP 数据包
                sock.receive(packet)

                // 解析 RTP 头
                val payload = parseRtpPacket(packet.data, packet.length)

                if (payload != null && payload.size > 0) {
                    // 收集 NALU
                    collectNalu(payload, naluBuffer)

                    // 如果是完整的帧，送入解码器
                    if (isCompleteFrame(payload)) {
                        val frameData = naluBuffer.toByteArray()
                        naluBuffer.reset()

                        // 送入解码器
                        decodeFrame(dec, frameData, outputBuffer, bufferInfo)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Receive error", e)
                }
            }
        }
    }

    /**
     * 解析 RTP 数据包，返回 H.264 payload
     */
    private fun parseRtpPacket(data: ByteArray, length: Int): ByteArray? {
        if (length < RTP_HEADER_SIZE) return null

        // 简单的 RTP 头解析
        // Byte 0: V(2) P(1) X(1) CC(4) M(1) PT(7)
        val firstByte = data[0].toInt() and 0xFF
        val payloadType = firstByte and 0x7F

        if (payloadType != PAYLOAD_TYPE_H264) {
            // 不是 H.264 payload
            return null
        }

        // 跳过 RTP 头
        val headerSize = RTP_HEADER_SIZE
        if (length <= headerSize) return null

        return data.sliceArray(headerSize until length)
    }

    /**
     * 收集 NALU 数据
     */
    private fun collectNalu(payload: ByteArray, buffer: ByteArrayOutputStream) {
        // H.264 NALU 格式：[Start Code] + NALU type + ...
        // 简化处理：直接写入，让 MediaCodec 处理
        buffer.write(payload)
    }

    /**
     * 判断是否为完整帧
     * 这是一个简化的判断，实际应该检查 NALU 类型
     */
    private fun isCompleteFrame(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false

        val nalType = payload[0].toInt() and 0x1F

        // I-frame (5) 或 P-frame (1) 通常是完整帧
        // SPS (7), PPS (8), SEI (6) 不触发解码
        return nalType in listOf(1, 5) || payload[0].toInt() == 0 // 0x00 表示开始码
    }

    /**
     * 解码一帧
     */
    private fun decodeFrame(
        decoder: MediaCodec,
        frameData: ByteArray,
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        val inputIndex = decoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(frameData)
            decoder.queueInputBuffer(inputIndex, 0, frameData.size, System.nanoTime() / 1000, 0)

            // 解码
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputIndex >= 0) {
                val outBuffer = decoder.getOutputBuffer(outputIndex)
                outBuffer?.let {
                    it.position(bufferInfo.offset)
                    it.limit(bufferInfo.offset + bufferInfo.size)
                    // 输出到 Surface（自动渲染）
                    decoder.releaseOutputBuffer(outputIndex, true)
                }
            }
        }
    }
}
