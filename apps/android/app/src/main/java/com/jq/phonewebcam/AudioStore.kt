package com.jq.phonewebcam

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class AudioChunk(
    val sequence: Long,
    val bytes: ByteArray
)

/**
 * Ring buffer of the most recent PCM chunks (FrameStore pattern, but keeping a short
 * history so a momentarily slow reader doesn't lose audio). A reader that falls behind
 * the ring jumps to the oldest retained chunk: one audible glitch, bounded latency.
 */
class AudioStore {
    private val lock = ReentrantLock()
    private val chunkAvailable = lock.newCondition()
    private val chunks = ArrayDeque<AudioChunk>()
    private var sequence = 0L

    fun update(bytes: ByteArray) {
        lock.withLock {
            sequence += 1
            chunks.addLast(AudioChunk(sequence, bytes))
            while (chunks.size > MaxChunks) {
                chunks.removeFirst()
            }
            chunkAvailable.signalAll()
        }
    }

    /** Blocks until a chunk newer than [lastSequence] exists, or the timeout passes. */
    fun waitForNext(lastSequence: Long, timeoutMs: Long): AudioChunk? {
        return lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (sequence <= lastSequence) {
                if (remainingNanos <= 0L) {
                    return@withLock null
                }
                try {
                    remainingNanos = chunkAvailable.awaitNanos(remainingNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withLock null
                }
            }
            // Oldest retained chunk newer than lastSequence (jump forward if we fell
            // out of the ring).
            chunks.firstOrNull { it.sequence > lastSequence }
        }
    }

    fun clear() {
        lock.withLock {
            chunks.clear()
            chunkAvailable.signalAll()
        }
    }

    companion object {
        // 50 x 20ms = one second of retained audio.
        private const val MaxChunks = 50
    }
}
