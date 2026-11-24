package com.opentalk.android.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.tanh

object CallRecorder {
    private const val TAG = "CallRecorder"
    private const val SAMPLE_RATE = 8000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val FRAME_BYTES = 320 // 20ms of 16-bit 8kHz mono PCM
    private const val FRAME_INTERVAL_NS = 20_000_000L

    private class FrameAccumulator(
        val buffer: ByteArray = ByteArray(FRAME_BYTES),
        var filled: Int = 0
    )

    private class RecordingSession(
        val file: File,
        val active: AtomicBoolean = AtomicBoolean(true),
        val headerWritten: AtomicBoolean = AtomicBoolean(false),
        val localQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(250),
        val remoteQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(250),
        val localAcc: FrameAccumulator = FrameAccumulator(),
        val remoteAcc: FrameAccumulator = FrameAccumulator()
    )

    @Volatile
    private var activeSession: RecordingSession? = null

    private var recordThread: Thread? = null

    val isRecording: Boolean
        get() = activeSession?.active?.get() == true

    @Synchronized
    fun startRecording(context: Context, remoteNumber: String, remoteName: String): File? {
        activeSession?.let { existing ->
            if (existing.active.get()) {
                Log.w(TAG, "Recording is already active")
                return existing.file
            }
        }

        try {
            val baseDir = context.applicationContext.getExternalFilesDir("Recordings")
                ?: File(context.applicationContext.filesDir, "Recordings")
            val dir = File(baseDir, "OpenTalkCalls").apply {
                if (!exists()) mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val cleanNumber = remoteNumber.filter { it.isDigit() || it == '+' }.ifEmpty { "Call" }
            val cleanNameTag = if (remoteName.isNotBlank() && remoteName != remoteNumber) {
                val sanitized = remoteName.filter { it.isLetterOrDigit() }.take(12)
                if (sanitized.isNotBlank()) "_$sanitized" else ""
            } else ""
            val file = File(dir, "REC_${cleanNumber}${cleanNameTag}_$timeStamp.wav")

            val session = RecordingSession(file)
            activeSession = session

            recordThread = Thread({
                var fos: FileOutputStream? = null
                var totalPcmBytes = 0L

                try {
                    fos = FileOutputStream(session.file)
                    fos.write(ByteArray(44))

                    val zeroFrame = ByteArray(FRAME_BYTES)
                    val mixedFrame = ByteArray(FRAME_BYTES)
                    var nextTickNs = SystemClock.elapsedRealtimeNanos()

                    fun mixAndWrite(loc: ByteArray, rem: ByteArray) {
                        for (i in 0 until FRAME_BYTES / 2) {
                            val idx = i * 2
                            val s1 = ((loc[idx + 1].toInt() shl 8) or (loc[idx].toInt() and 0xFF)).toShort().toDouble()
                            val s2 = ((rem[idx + 1].toInt() shl 8) or (rem[idx].toInt() and 0xFF)).toShort().toDouble()
                            // Soft-knee limiter prevents harsh clipping during simultaneous double-talk
                            var mixed = s1 + s2
                            if (mixed > 26000.0) {
                                mixed = 26000.0 + 6500.0 * tanh((mixed - 26000.0) / 14000.0)
                            } else if (mixed < -26000.0) {
                                mixed = -26000.0 - 6500.0 * tanh((-mixed - 26000.0) / 14000.0)
                            }
                            val clamped = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            mixedFrame[idx] = (clamped and 0xFF).toByte()
                            mixedFrame[idx + 1] = ((clamped shr 8) and 0xFF).toByte()
                        }
                        fos.write(mixedFrame, 0, FRAME_BYTES)
                        totalPcmBytes += FRAME_BYTES
                    }

                    // Monotonic 20ms mixer loop: keeps local and remote audio strictly synchronized
                    while (session.active.get()) {
                        nextTickNs += FRAME_INTERVAL_NS
                        val waitNs = nextTickNs - SystemClock.elapsedRealtimeNanos()
                        if (waitNs > 1_000_000L) {
                            java.util.concurrent.locks.LockSupport.parkNanos(waitNs)
                        } else if (waitNs < -100_000_000L) {
                            nextTickNs = SystemClock.elapsedRealtimeNanos()
                        }

                        // Drop excessive drift (> 6 frames = 120ms) if one stream briefly outpaced the other
                        while (session.localQueue.size > 6) session.localQueue.poll()
                        while (session.remoteQueue.size > 6) session.remoteQueue.poll()

                        val local = session.localQueue.poll()
                        val remote = session.remoteQueue.poll()
                        if (local == null && remote == null) continue
                        mixAndWrite(local ?: zeroFrame, remote ?: zeroFrame)
                    }

                    // Fast non-blocking drain (< 5ms) so stopRecording() always gets a complete WAV header
                    while (session.localQueue.isNotEmpty() || session.remoteQueue.isNotEmpty()) {
                        val local = session.localQueue.poll()
                        val remote = session.remoteQueue.poll()
                        if (local == null && remote == null) break
                        mixAndWrite(local ?: zeroFrame, remote ?: zeroFrame)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing call audio recording: ${e.message}", e)
                } finally {
                    session.active.set(false)
                    session.localQueue.clear()
                    session.remoteQueue.clear()
                    try {
                        fos?.flush()
                        fos?.close()
                    } catch (_: Exception) {}

                    if (fos != null && totalPcmBytes > 0L) {
                        try {
                            writeWavHeader(session.file, totalPcmBytes)
                            session.headerWritten.set(true)
                            Log.i(TAG, "Recording saved: ${session.file.absolutePath} ($totalPcmBytes bytes PCM)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write WAV header: ${e.message}")
                            try { session.file.delete() } catch (_: Exception) {}
                        }
                    } else {
                        try { session.file.delete() } catch (_: Exception) {}
                        Log.w(TAG, "Deleted empty/failed recording file: ${session.file.name}")
                    }
                    if (activeSession === session) {
                        activeSession = null
                    }
                }
            }, "CallRecorderThread").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }

            Log.i(TAG, "Started call recording to ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start call recording: ${e.message}", e)
            activeSession?.active?.set(false)
            activeSession = null
            return null
        }
    }

    @Synchronized
    fun stopRecording(): File? {
        val session = activeSession ?: return null
        session.active.set(false)
        val thread = recordThread
        if (thread != null) {
            java.util.concurrent.locks.LockSupport.unpark(thread)
        }
        try {
            thread?.join(2000)
        } catch (_: Exception) {}
        recordThread = null
        if (activeSession === session) {
            activeSession = null
        }
        return if (session.headerWritten.get() && session.file.exists() && session.file.length() > 44L) {
            session.file
        } else {
            null
        }
    }

    private fun enqueueAccumulated(
        queue: LinkedBlockingQueue<ByteArray>,
        acc: FrameAccumulator,
        pcm: ByteArray,
        offset: Int,
        length: Int
    ) {
        val end = minOf(offset + length, pcm.size)
        if (offset < 0 || offset >= end) return
        synchronized(acc) {
            var pos = offset
            while (pos < end) {
                val needed = FRAME_BYTES - acc.filled
                val take = minOf(needed, end - pos)
                System.arraycopy(pcm, pos, acc.buffer, acc.filled, take)
                acc.filled += take
                pos += take
                if (acc.filled == FRAME_BYTES) {
                    val frame = acc.buffer.copyOf()
                    acc.filled = 0
                    if (!queue.offer(frame)) {
                        queue.poll()
                        queue.offer(frame)
                    }
                }
            }
        }
    }

    fun writeLocalPcm(pcm: ByteArray, offset: Int, length: Int) {
        val session = activeSession ?: return
        if (!session.active.get() || length <= 0) return
        enqueueAccumulated(session.localQueue, session.localAcc, pcm, offset, length)
    }

    fun writeRemotePcm(pcm: ByteArray, offset: Int, length: Int) {
        val session = activeSession ?: return
        if (!session.active.get() || length <= 0) return
        enqueueAccumulated(session.remoteQueue, session.remoteAcc, pcm, offset, length)
    }

    private fun writeWavHeader(file: File, pcmDataLength: Long) {
        val totalDataLen = pcmDataLength + 36
        val byteRate = (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8).toLong()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = CHANNELS.toByte()
            header[23] = 0
            header[24] = (SAMPLE_RATE and 0xff).toByte()
            header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
            header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
            header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (CHANNELS * BITS_PER_SAMPLE / 8).toByte()
            header[33] = 0
            header[34] = BITS_PER_SAMPLE.toByte()
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (pcmDataLength and 0xff).toByte()
            header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
            header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
            header[43] = ((pcmDataLength shr 24) and 0xff).toByte()
            raf.write(header, 0, 44)
        }
    }
}
