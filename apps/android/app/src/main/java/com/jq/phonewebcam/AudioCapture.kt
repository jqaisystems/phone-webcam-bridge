package com.jq.phonewebcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Captures the phone microphone as 48 kHz mono 16-bit PCM in 20 ms chunks and feeds
 * them into [AudioStore]. 48 kHz matches Web Audio's native rate on the PC so nothing
 * resamples anywhere. Raw PCM (768 kbps) is trivial next to the MJPEG video stream.
 */
class AudioCapture(
    private val audioStore: AudioStore
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /** Requires RECORD_AUDIO to be granted; returns false when the mic can't start. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) {
            return true
        }

        val minBuffer = AudioRecord.getMinBufferSize(SampleRate, ChannelConfig, Encoding)
        if (minBuffer <= 0) {
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SampleRate,
                ChannelConfig,
                Encoding,
                maxOf(minBuffer * 4, ChunkBytes * 8)
            )
        } catch (_: Exception) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        running = true
        thread = Thread {
            try {
                record.startRecording()
                val chunk = ByteArray(ChunkBytes)
                while (running) {
                    var offset = 0
                    while (offset < ChunkBytes && running) {
                        val read = record.read(chunk, offset, ChunkBytes - offset)
                        if (read <= 0) {
                            // Recording died (device revoked the mic); stop cleanly.
                            running = false
                            break
                        }
                        offset += read
                    }
                    if (offset == ChunkBytes) {
                        audioStore.update(chunk.copyOf())
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                    // Already stopped.
                }
                record.release()
            }
        }.apply {
            name = "audio-capture"
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        audioStore.clear()
    }

    companion object {
        const val SampleRate = 48000
        const val Channels = 1
        const val BitsPerSample = 16
        private const val ChannelConfig = AudioFormat.CHANNEL_IN_MONO
        private const val Encoding = AudioFormat.ENCODING_PCM_16BIT

        // 20 ms of 48 kHz mono 16-bit = 960 samples = 1920 bytes.
        const val ChunkBytes = SampleRate / 50 * (BitsPerSample / 8) * Channels
    }
}
