package io.github.miuzarte.scrcpyforandroid.nativecore

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class AudioInjector {

    companion object {
        private const val TAG         = "AudioInjector"
        private const val PORT        = 7008
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FMT   = AudioFormat.ENCODING_PCM_16BIT
        private const val OPUS_BITRATE = 24000
        private const val FRAME_SIZE  = SAMPLE_RATE / 50  // 320 samples = 20ms
    }

    private var audioRecord: AudioRecord?     = null
    private var socket:     Socket?           = null
    private var output:     DataOutputStream? = null
    private var encoder:    MediaCodec?       = null
    @Volatile private var capturing = false

    fun start(deviceHost: String) {
        if (capturing) return

        Thread({
            try {
                // 1. 初始化麦克风
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT)
                if (minBuf <= 0) {
                    Log.e(TAG, "getMinBufferSize=$minBuf")
                    return@Thread
                }
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT,
                    Math.max(minBuf, 8192)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord not initialized")
                    rec.release()
                    return@Thread
                }
                audioRecord = rec

                // 2. 连接 TCP
                socket = Socket()
                socket!!.connect(InetSocketAddress(deviceHost, PORT), 5000)
                socket!!.tcpNoDelay = true
                socket!!.sendBufferSize = 1024
                output = DataOutputStream(socket!!.getOutputStream())
                Log.i(TAG, "TCP connected: $deviceHost:$PORT")

                // 3. 尝试 Opus 编码，失败自动回退 PCM
                val opusResult = tryInitOpus()
                val out = output!!

                rec.startRecording()
                capturing = true

                if (opusResult != null) {
                    Log.i(TAG, "Opus mode")
                    val (enc, csdList) = opusResult
                    // 发送 Opus 握手
                    out.writeInt(0x4F505553)  // "OPUS"
                    out.writeInt(csdList.size)
                    for (csd in csdList) {
                        out.writeInt(csd.size)
                        out.write(csd)
                    }
                    out.flush()
                    opusLoop(rec, enc, out)
                } else {
                    Log.i(TAG, "PCM fallback mode")
                    pcmLoop(rec, out)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
            } finally {
                stop()
            }
        }, "AudioInjector").start()
    }

    // ---------- Opus 初始化（返回 null 表示失败） ----------

    private data class OpusInit(val enc: MediaCodec, val csd: List<ByteArray>)

    private fun tryInitOpus(): OpusInit? {
        return try {
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoder = enc
            Log.i(TAG, "Opus encoder started ($OPUS_BITRATE bps)")

            val csdList = extractCsd(enc)
            if (csdList.isEmpty()) {
                Log.e(TAG, "CSD extraction failed, falling back to PCM")
                enc.stop()
                enc.release()
                encoder = null
                return null
            }
            Log.i(TAG, "CSD: ${csdList.size} entries, sizes=${csdList.map { it.size }}")
            OpusInit(enc, csdList)
        } catch (e: Exception) {
            Log.w(TAG, "Opus unavailable: ${e.message}, falling back to PCM")
            try { encoder?.release() } catch (_: Exception) {}
            encoder = null
            null
        }
    }

    private fun extractCsd(enc: MediaCodec): List<ByteArray> {
        val result = mutableListOf<ByteArray>()

        // 送静音帧触发编码器初始化
        val silence = ByteArray(FRAME_SIZE * 2)
        val inIdx = enc.dequeueInputBuffer(100000)
        if (inIdx >= 0) {
            val buf = enc.getInputBuffer(inIdx)
            buf!!.clear()
            buf.put(silence)
            enc.queueInputBuffer(inIdx, 0, silence.size, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        var gotFormat = false
        val deadline = System.nanoTime() + 1_000_000_000L  // 1s

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

    // ---------- Opus 编码循环 ----------

    private fun opusLoop(rec: AudioRecord, enc: MediaCodec, out: DataOutputStream) {
        val pcmBuf  = ShortArray(FRAME_SIZE)
        val byteBuf = ByteArray(FRAME_SIZE * 2)
        val info    = MediaCodec.BufferInfo()
        var frameCount = 0

        while (capturing) {
            val n = rec.read(pcmBuf, 0, FRAME_SIZE)
            if (n <= 0) break

            val len = n * 2
            for (i in 0 until n) {
                byteBuf[i * 2]     = (pcmBuf[i].toInt() and 0xFF).toByte()
                byteBuf[i * 2 + 1] = (pcmBuf[i].toInt() shr 8).toByte()
            }

            // 送入编码器
            val inIdx = enc.dequeueInputBuffer(10000)
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx)
                inBuf!!.clear()
                inBuf.put(byteBuf, 0, len)
                enc.queueInputBuffer(inIdx, 0, len, frameCount * 20000L, 0)
            }

            // 排空编码器输出
            while (capturing) {
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

                out.writeInt(info.size)
                out.write(data)
                out.flush()

                enc.releaseOutputBuffer(outIdx, false)
                frameCount++
                if (frameCount % 250 == 0) {
                    Log.i(TAG, "[Opus] #$frameCount (${info.size}B)")
                }
            }
        }
        Log.i(TAG, "[Opus] ended, frames=$frameCount")
    }

    // ---------- PCM 直传循环（回退模式） ----------

    private fun pcmLoop(rec: AudioRecord, out: DataOutputStream) {
        val buffer  = ShortArray(FRAME_SIZE)
        val byteBuf = ByteArray(FRAME_SIZE * 2)
        var count   = 0

        while (capturing) {
            val n = rec.read(buffer, 0, FRAME_SIZE)
            if (n <= 0) break

            try {
                val len = n * 2
                for (i in 0 until n) {
                    byteBuf[i * 2]     = (buffer[i].toInt() and 0xFF).toByte()
                    byteBuf[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                }
                out.writeInt(len)
                out.write(byteBuf, 0, len)
                out.flush()
                count++
                if (count % 250 == 0) {
                    Log.i(TAG, "[PCM] #$count")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[PCM] send error: ${e.message}")
                break
            }
        }
        Log.i(TAG, "[PCM] ended, sent=$count")
    }

    // ---------- cleanup ----------

    fun stop() {
        capturing = false
        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { encoder?.stop() }    catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() }  catch (_: Exception) {}
        socket  = null
        output  = null
    }
}
