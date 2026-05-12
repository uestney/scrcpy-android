package io.github.miuzarte.scrcpyforandroid.nativecore

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

object MicTestUtil {

    private const val TAG = "MicTest"
    private const val SAMPLE_RATE = 44100
    private const val CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
    private const val DURATION_S  = 3

    var lastError: String? = null
        private set

    fun record(): ByteArray? {
        lastError = null
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        Log.i(TAG, "getMinBufferSize=$minBuf")

        if (minBuf <= 0) {
            lastError = "getMinBufferSize=$minBuf"
            return null
        }

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            Math.max(minBuf, 8192)
        )

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            lastError = "state=${rec.state}"
            rec.release()
            return null
        }

        rec.startRecording()
        Log.i(TAG, "startRecording, state=${rec.recordingState}")

        val frameSize = SAMPLE_RATE / 50   // 882 shorts = 20ms
        val totalFrames = SAMPLE_RATE / frameSize * DURATION_S  // 3 seconds
        val buffer = ShortArray(frameSize)
        val pcm = ShortArray(totalFrames * frameSize)
        var offset = 0

        for (i in 0 until totalFrames) {
            val n = rec.read(buffer, 0, buffer.size)
            if (n <= 0) {
                lastError = "read returned $n at frame $i"
                Log.e(TAG, lastError!!)
                rec.stop()
                rec.release()
                return null
            }
            val copyLen = Math.min(n, pcm.size - offset)
            if (copyLen > 0) {
                System.arraycopy(buffer, 0, pcm, offset, copyLen)
                offset += copyLen
            }
        }

        rec.stop()
        rec.release()
        Log.i(TAG, "Recorded ${offset} shorts (${offset * 2} bytes)")

        // short[] → byte[]
        val bytes = ByteArray(offset * 2)
        for (i in 0 until offset) {
            bytes[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return bytes
    }

    fun play(pcm: ByteArray) {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .build())
            .setBufferSizeInBytes(Math.max(minBuf, pcm.size))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        track.write(pcm, 0, pcm.size)
        // 等播放完毕再 stop（数据时长 = pcm.size / (SAMPLE_RATE * 2)）
        val durationMs = pcm.size.toLong() * 1000 / (SAMPLE_RATE * 2)
        Thread.sleep(durationMs + 200)
        track.stop()
        track.release()
        Log.i(TAG, "Playback done: ${pcm.size} bytes, ${durationMs}ms")
    }
}
