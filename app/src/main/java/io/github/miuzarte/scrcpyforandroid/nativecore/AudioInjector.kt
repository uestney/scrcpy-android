package io.github.miuzarte.scrcpyforandroid.nativecore

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class AudioInjector {

    companion object {
        private const val TAG         = "AudioInjector"
        private const val PORT         = 7008
        private const val SAMPLE_RATE  = 44100
    }

    private var audioRecord: AudioRecord?     = null
    private var socket:     Socket?           = null
    private var output:     DataOutputStream? = null
    @Volatile private var capturing = false
    @Volatile var ready     = false

    fun start(deviceHost: String) {
        if (capturing) return

        Thread({
            try {
                val channelCfg = AudioFormat.CHANNEL_IN_MONO
                val audioFmt   = AudioFormat.ENCODING_PCM_16BIT
                val minBuf     = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelCfg, audioFmt)
                Log.i(TAG, "minBuf=$minBuf")

                if (minBuf <= 0) {
                    Log.e(TAG, "getMinBufferSize returned $minBuf, abort")
                    return@Thread
                }

                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    channelCfg,
                    audioFmt,
                    Math.max(minBuf, 8192)
                )

                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord not initialized: ${rec.state}")
                    rec.release()
                    return@Thread
                }

                audioRecord = rec
                rec.startRecording()
                Log.i(TAG, "Recording started, state=${rec.recordingState}")

                // 等 200ms 让 HAL 初始化
                Thread.sleep(200)

                // 先读一帧试试
                val frameSize = SAMPLE_RATE / 50  // 20ms = 882 shorts
                val testBuf = ShortArray(frameSize)
                val testN = rec.read(testBuf, 0, testBuf.size, 5000)
                Log.i(TAG, "Test read: n=$testN")

                if (testN <= 0) {
                    Log.e(TAG, "Test read failed, abort")
                    rec.stop()
                    rec.release()
                    audioRecord = null
                    return@Thread
                }

                // 麦克风可用！现在连接 TCP
                Log.i(TAG, "Mic OK, connecting TCP")
                socket = Socket()
                socket!!.connect(InetSocketAddress(deviceHost, PORT), 5000)
                socket!!.tcpNoDelay = true
                output = DataOutputStream(socket!!.getOutputStream())
                Log.i(TAG, "Connected to $deviceHost:$PORT")

                capturing = true
                ready = true

                // 发送第一帧
                val out = output!!
                out.writeInt(testN * 2)
                for (i in 0 until testN) out.writeShort(testBuf[i].toInt())
                out.flush()
                Log.i(TAG, "First frame sent: ${testN * 2} bytes")

                // 持续采集发送
                val buffer = ShortArray(frameSize)
                var count = 1
                while (capturing) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n <= 0) {
                        Log.w(TAG, "read returned $n")
                        break
                    }
                    try {
                        out.writeInt(n * 2)
                        for (i in 0 until n) out.writeShort(buffer[i].toInt())
                        out.flush()
                        count++
                        if (count % 250 == 0) Log.i(TAG, "Sent #$count")
                    } catch (e: Exception) {
                        Log.e(TAG, "Send error: ${e.message}")
                        break
                    }
                }
                Log.i(TAG, "Ended, sent=$count")

            } catch (e: Exception) {
                Log.e(TAG, "Failed: ${e.message}", e)
            } finally {
                stop()
            }
        }, "AudioInjector").start()
    }

    fun stop() {
        capturing = false
        ready = false
        try { audioRecord?.stop() }   catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket  = null
        output  = null
    }
}
