package com.tcs.vehicleassistant.hardware.ear

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A thread-safe ring buffer for raw PCM float data (16kHz).
 * Designed to hold ~1.5s of audio to prevent first-word clipping when transitioning from
 * wake word detection to speech recognition.
 */
class PcmRingBuffer(capacitySamples: Int = 24_000) {

    private val buffer = FloatArray(capacitySamples)
    private var writePos = 0
    private var isFull = false
    private val lock = ReentrantLock()

    /**
     * Pushes new samples into the ring buffer.
     */
    fun push(samples: FloatArray, length: Int) {
        if (length <= 0) return
        lock.withLock {
            if (length >= buffer.size) {
                // If incoming chunk is larger than our buffer, just take the tail
                val offset = length - buffer.size
                System.arraycopy(samples, offset, buffer, 0, buffer.size)
                writePos = 0
                isFull = true
                return
            }

            val spaceToEnd = buffer.size - writePos
            if (length <= spaceToEnd) {
                System.arraycopy(samples, 0, buffer, writePos, length)
                writePos += length
                if (writePos == buffer.size) {
                    writePos = 0
                    isFull = true
                }
            } else {
                // Wrap around
                System.arraycopy(samples, 0, buffer, writePos, spaceToEnd)
                val remaining = length - spaceToEnd
                System.arraycopy(samples, spaceToEnd, buffer, 0, remaining)
                writePos = remaining
                isFull = true
            }
        }
    }

    /**
     * Returns a snapshot of the oldest to newest samples currently in the buffer,
     * and clears the buffer.
     */
    fun snapshotAndClear(): FloatArray {
        lock.withLock {
            val result = if (!isFull) {
                if (writePos == 0) {
                    FloatArray(0)
                } else {
                    val arr = FloatArray(writePos)
                    System.arraycopy(buffer, 0, arr, 0, writePos)
                    arr
                }
            } else {
                val arr = FloatArray(buffer.size)
                val tailSize = buffer.size - writePos
                System.arraycopy(buffer, writePos, arr, 0, tailSize)
                System.arraycopy(buffer, 0, arr, tailSize, writePos)
                arr
            }
            clearLocked()
            return result
        }
    }

    fun clear() {
        lock.withLock {
            clearLocked()
        }
    }

    private fun clearLocked() {
        writePos = 0
        isFull = false
        // We don't strictly need to zero the array since we track bounds with writePos/isFull
    }
}
