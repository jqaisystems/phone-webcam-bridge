package com.jq.phonewebcam

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class FrameSnapshot(
    val sequence: Long,
    val bytes: ByteArray
)

class FrameStore {
    private val lock = ReentrantLock()
    private val frameAvailable = lock.newCondition()
    private var sequence = 0L
    private var frame: ByteArray? = null
    private var lastProducerSeq = Long.MIN_VALUE

    // Rolling window for the real produced frame rate, reported in /status.
    private var fpsWindowStartNanos = 0L
    private var fpsWindowCount = 0
    private var measuredFps = 0.0

    /**
     * Stores a new frame. When [producerSeq] is provided (parallel encoders can finish
     * out of order), frames older than the newest stored one are dropped.
     */
    fun update(bytes: ByteArray, producerSeq: Long = Long.MIN_VALUE) {
        lock.withLock {
            if (producerSeq != Long.MIN_VALUE) {
                if (producerSeq <= lastProducerSeq) {
                    return
                }
                lastProducerSeq = producerSeq
            }
            frame = bytes
            sequence += 1

            val now = System.nanoTime()
            if (fpsWindowStartNanos == 0L) {
                fpsWindowStartNanos = now
            }
            fpsWindowCount += 1
            val elapsedNanos = now - fpsWindowStartNanos
            if (elapsedNanos >= FpsWindowNanos) {
                measuredFps = fpsWindowCount * 1_000_000_000.0 / elapsedNanos
                fpsWindowStartNanos = now
                fpsWindowCount = 0
            }

            frameAvailable.signalAll()
        }
    }

    /** Real produced frame rate over the last measurement window (0 until warm). */
    fun currentFps(): Double {
        return lock.withLock {
            // If production stalled, don't keep reporting the last healthy value.
            val sinceWindowStart = System.nanoTime() - fpsWindowStartNanos
            if (fpsWindowStartNanos == 0L || sinceWindowStart > FpsWindowNanos * 2) 0.0 else measuredFps
        }
    }

    fun latest(): FrameSnapshot? {
        return lock.withLock {
            val current = frame ?: return@withLock null
            FrameSnapshot(sequence, current)
        }
    }

    fun waitForNext(lastSequence: Long, timeoutMs: Long): FrameSnapshot? {
        return lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (sequence <= lastSequence) {
                if (remainingNanos <= 0L) {
                    return@withLock null
                }

                try {
                    remainingNanos = frameAvailable.awaitNanos(remainingNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withLock null
                }
            }
            val current = frame ?: return@withLock null
            FrameSnapshot(sequence, current)
        }
    }

    companion object {
        private const val FpsWindowNanos = 3_000_000_000L
    }
}
