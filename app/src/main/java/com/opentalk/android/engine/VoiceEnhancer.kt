package com.opentalk.android.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Studio-Clean, Transparent Telephony Audio Processor (ITU-T G.711 / 8 kHz):
 *
 * Upgraded specifically to deliver crystal-clear, natural, noise-free Original Voice:
 *
 * 1. Mic Path (0 ms extra latency, zero pitch/identity alteration):
 *    - 78 Hz 2nd-order Butterworth HPF: removes DC bias, 50/60 Hz AC mains hum, wind, and handling rumble
 *      while preserving 100% of deep male fundamental frequencies.
 *    - 3480 Hz 2nd-order Butterworth LPF: smoothly strips out-of-band near-Nyquist ADC hiss before G.711.
 *    - Subtle Broadcast Clarity Polish (+1.2 dB broad bell at 2350 Hz, Q = 0.80): restores natural Bengali
 *      consonant crispness over 8 kHz narrowband telephony with zero metallic harshness or tone change.
 *    - Complementary 2-Band Adaptive Noise Softener (exact low + high = x split at 650 Hz):
 *      tracks low-frequency fan/AC/traffic rumble and high-frequency mic hiss independently; completely
 *      transparent (1.0x exact unity) during active speech, and glides smoothly down to -12 dB during
 *      pauses with fast click-free attack and natural 360 ms hangover (zero word-clipping, zero pumping).
 *    - Conservative speech-only ALC (only adapts on clean high-SNR speech, NEVER amplifies background noise)
 *      + smooth tanh soft-knee peak limiter at 26,000 to prevent harsh G.711 crackling.
 *
 * 2. Speaker Path:
 *    - 120 Hz 2nd-order Butterworth HPF + smooth 3200 Hz 2nd-order Butterworth LPF.
 *    - Gentle idle-line hiss softener (-6 dB) with sample-interpolated ramp.
 *    - Speech-only level protection + earpiece anti-distortion soft limiter.
 */
object VoiceEnhancer {

    // 2nd-order Biquad filter (RBJ Direct Form I, Double precision internals for zero limit-cycle hum)
    private class BiquadFilter(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }

        companion object {
            fun highPass(freq: Double, sampleRate: Double, q: Double = 0.70710678): BiquadFilter {
                val w0 = 2.0 * PI * freq / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosw0 = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    (1.0 + cosw0) / 2.0 / a0,
                    -(1.0 + cosw0) / a0,
                    (1.0 + cosw0) / 2.0 / a0,
                    (-2.0 * cosw0) / a0,
                    (1.0 - alpha) / a0
                )
            }

            fun lowPass(freq: Double, sampleRate: Double, q: Double = 0.70710678): BiquadFilter {
                val w0 = 2.0 * PI * freq / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosw0 = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    ((1.0 - cosw0) / 2.0) / a0,
                    (1.0 - cosw0) / a0,
                    ((1.0 - cosw0) / 2.0) / a0,
                    (-2.0 * cosw0) / a0,
                    (1.0 - alpha) / a0
                )
            }

            fun peaking(freq: Double, sampleRate: Double, gainDb: Double, q: Double): BiquadFilter {
                val a = Math.pow(10.0, gainDb / 40.0)
                val w0 = 2.0 * PI * freq / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosw0 = cos(w0)
                val a0 = 1.0 + alpha / a
                return BiquadFilter(
                    (1.0 + alpha * a) / a0,
                    (-2.0 * cosw0) / a0,
                    (1.0 - alpha * a) / a0,
                    (-2.0 * cosw0) / a0,
                    (1.0 - alpha / a) / a0
                )
            }
        }
    }

    private val micLock = Any()
    private val spkLock = Any()

    // Mic Filters (8 kHz):
    // - 78 Hz Butterworth HPF (cuts DC, 50/60 Hz Bangladesh mains hum, handling thumps; preserves deep male F0!)
    // - 3480 Hz Butterworth LPF (eliminates near-Nyquist ADC hiss cleanly before G.711 encoding)
    // - Subtle +1.2 dB broad presence polish at 2350 Hz (Q=0.80) for crisp Bengali speech clarity without changing voice identity
    private var micHpf = BiquadFilter.highPass(78.0, 8000.0)
    private var micLpf = BiquadFilter.lowPass(3480.0, 8000.0)
    private var micClarityEq = BiquadFilter.peaking(2350.0, 8000.0, 1.2, 0.80)

    // Pre-allocated zero-GC scratch buffers for complementary 2-band noise softener (max 320 samples = 40 ms)
    private val micLowScratch = DoubleArray(320)
    private val micHighScratch = DoubleArray(320)
    // 1-pole complementary crossover at 650 Hz @ 8 kHz: alpha = 1 - exp(-2*pi*650/8000) ≈ 0.3998
    private const val SPLIT_ALPHA_650HZ = 0.3998
    private var micSplitLpState = 0.0

    // Speaker Filters (8 kHz): 120 Hz HPF + single smooth 3200 Hz LPF (removes G.711 hiss cleanly without ringing)
    private var spkHpf = BiquadFilter.highPass(120.0, 8000.0)
    private var spkLpf = BiquadFilter.lowPass(3200.0, 8000.0)

    // Mic Adaptive 2-Band Noise Floor & VAD State
    @Volatile private var micNoiseFloor = 80.0
    @Volatile private var micLowNoiseFloor = 55.0
    @Volatile private var micHighNoiseFloor = 45.0
    @Volatile private var micLowBandGain = 1.0f
    @Volatile private var micHighBandGain = 1.0f
    @Volatile private var micSpeechHangover = 0
    @Volatile private var isVoiceActiveNow = true

    // Speaker Idle Hiss Softener State
    @Volatile private var spkIdleFrames = 0
    @Volatile private var spkGateGain = 1.0f

    // Speech-only Automatic Level Control (ALC) State
    @Volatile private var micAlcGain = 1.0f
    @Volatile private var spkAlcGain = 1.0f

    private const val MIC_LOW_NOISE_FLOOR_GAIN = 0.28f   // Smooth -11.1 dB suppression of fan/AC/traffic rumble in pauses
    private const val MIC_HIGH_NOISE_FLOOR_GAIN = 0.24f  // Smooth -12.4 dB suppression of mic hiss in pauses
    private const val SPK_IDLE_FLOOR = 0.50f             // Gentle -6.0 dB floor on pure PSTN line hiss during silence
    private const val TARGET_VOICE_RMS = 3350.0          // Crisp, pleasant conversational loudness (~ -19.8 dBFS)

    @Volatile
    private var lastIncomingRms = 0.0

    fun getLastIncomingRms(): Double = lastIncomingRms

    fun reset() {
        synchronized(micLock) {
            micHpf.reset()
            micLpf.reset()
            micClarityEq.reset()
            micSplitLpState = 0.0
            micNoiseFloor = 80.0
            micLowNoiseFloor = 55.0
            micHighNoiseFloor = 45.0
            micLowBandGain = 1.0f
            micHighBandGain = 1.0f
            micSpeechHangover = 0
            isVoiceActiveNow = true
            micAlcGain = 1.0f
        }
        synchronized(spkLock) {
            spkHpf.reset()
            spkLpf.reset()
            spkIdleFrames = 0
            spkGateGain = 1.0f
            spkAlcGain = 1.0f
            lastIncomingRms = 0.0
        }
    }

    fun isMicVoiceActive(): Boolean = isVoiceActiveNow

    private var spkScratch = DoubleArray(480)

    /**
     * Mic path (returns true if voice is active in this 20 ms frame).
     * 100% pitch-preserving, zero artificial artifacts, clean 2-band adaptive background noise reduction.
     */
    fun processMicPcm(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val safeEnd = minOf(offset + length, bytes.size)
        if (offset < 0 || offset >= safeEnd) return false
        val maxChunkBytes = micLowScratch.size * 2
        var pos = offset
        var anyVoice = false
        while (pos < safeEnd) {
            val chunkLen = minOf(maxChunkBytes, safeEnd - pos)
            if (processMicPcmChunk(bytes, pos, chunkLen)) anyVoice = true
            pos += chunkLen
        }
        return anyVoice
    }

    private fun processMicPcmChunk(bytes: ByteArray, offset: Int, length: Int): Boolean = synchronized(micLock) {
        val sampleCount = (length / 2).coerceAtMost(micLowScratch.size)
        if (sampleCount <= 0) return@synchronized false

        // 1. Run 78 Hz HPF -> 3480 Hz LPF -> subtle 2350 Hz clarity polish, and split cleanly into
        //    exact complementary low (<650 Hz) + high (>=650 Hz) bands (low + high == x identically!)
        var sumSqTotal = 0.0
        var sumSqLow = 0.0
        var sumSqHigh = 0.0
        var lp = micSplitLpState

        for (i in 0 until sampleCount) {
            val byteIndex = offset + i * 2
            val lowB = bytes[byteIndex].toInt() and 0xFF
            val highB = bytes[byteIndex + 1].toInt()
            val inSample = ((highB shl 8) or lowB).toShort().toDouble()

            var clean = micHpf.process(inSample)
            clean = micLpf.process(clean)
            clean = micClarityEq.process(clean)

            lp += SPLIT_ALPHA_650HZ * (clean - lp)
            val lowBand = lp
            val highBand = clean - lp

            micLowScratch[i] = lowBand
            micHighScratch[i] = highBand

            sumSqTotal += clean * clean
            sumSqLow += lowBand * lowBand
            sumSqHigh += highBand * highBand
        }
        micSplitLpState = lp

        val frameRms = sqrt(sumSqTotal / sampleCount)
        val lowRms = sqrt(sumSqLow / sampleCount)
        val highRms = sqrt(sumSqHigh / sampleCount)

        // 2. Slow, stable asymmetric noise floor trackers (broadband + independent low & high bands)
        micNoiseFloor = if (frameRms < micNoiseFloor) {
            micNoiseFloor * 0.88 + frameRms * 0.12
        } else {
            micNoiseFloor * 0.996 + frameRms * 0.004
        }.coerceIn(22.0, 900.0)

        micLowNoiseFloor = if (lowRms < micLowNoiseFloor) {
            micLowNoiseFloor * 0.88 + lowRms * 0.12
        } else {
            micLowNoiseFloor * 0.996 + lowRms * 0.004
        }.coerceIn(16.0, 750.0)

        micHighNoiseFloor = if (highRms < micHighNoiseFloor) {
            micHighNoiseFloor * 0.88 + highRms * 0.12
        } else {
            micHighNoiseFloor * 0.996 + highRms * 0.004
        }.coerceIn(14.0, 650.0)

        // 3. Dual-band sensitive VAD (catches soft Bengali consonants like স/ফ/চ in high-band & vowels in low-band!)
        val snrTotal = frameRms / micNoiseFloor
        val snrLow = lowRms / micLowNoiseFloor
        val snrHigh = highRms / micHighNoiseFloor
        val isSpeechDetected = (snrTotal > 1.70 && frameRms > 105.0) ||
            (snrHigh > 2.05 && highRms > 80.0) ||
            (snrLow > 1.95 && lowRms > 110.0) ||
            frameRms > 880.0

        if (isSpeechDetected) {
            micSpeechHangover = 18 // 18 * 20 ms = 360 ms smooth hangover (preserves natural breathing & word tails)
        } else if (micSpeechHangover > 0) {
            micSpeechHangover--
        }
        val isVoiceActive = (isSpeechDetected || micSpeechHangover > 0)
        isVoiceActiveNow = isVoiceActive

        // 4. Conservative Speech-Only ALC (ONLY adapts on genuine clean speech, NEVER boosts room noise!)
        if (isSpeechDetected && frameRms > 550.0 && snrTotal > 2.0) {
            val desiredGain = when {
                frameRms < 1850.0 -> (TARGET_VOICE_RMS / frameRms).toFloat().coerceIn(1.0f, 1.32f)
                frameRms > 5400.0 -> (TARGET_VOICE_RMS / frameRms).toFloat().coerceIn(0.65f, 1.0f)
                else -> 1.0f
            }
            micAlcGain += (desiredGain - micAlcGain) * 0.025f
        }

        // 5. Smooth 2-Band Adaptive Noise Reduction (exact 1.0x during active speech; -11/-12 dB clean silence)
        //    During hangover tail (last 6 frames = 120 ms), gently feather bands only if that band is already at noise floor.
        val targetLowGain: Float
        val targetHighGain: Float
        if (isSpeechDetected) {
            targetLowGain = 1.0f
            targetHighGain = 1.0f
        } else if (micSpeechHangover > 0) {
            // Keep voice tails 100% intact; during final tail, gently relax quiet-only bands
            targetLowGain = if (micSpeechHangover <= 5 && snrLow < 1.35) 0.72f else 1.0f
            targetHighGain = if (micSpeechHangover <= 5 && snrHigh < 1.35) 0.68f else 1.0f
        } else {
            // Smooth continuous floor based on band SNR so gentle breaths never snap harshly
            val lowRatio = ((snrLow - 1.05) / 0.85).coerceIn(0.0, 1.0).toFloat()
            val highRatio = ((snrHigh - 1.05) / 0.85).coerceIn(0.0, 1.0).toFloat()
            targetLowGain = MIC_LOW_NOISE_FLOOR_GAIN + (1.0f - MIC_LOW_NOISE_FLOOR_GAIN) * lowRatio * 0.35f
            targetHighGain = MIC_HIGH_NOISE_FLOOR_GAIN + (1.0f - MIC_HIGH_NOISE_FLOOR_GAIN) * highRatio * 0.35f
        }

        val startLowGain = micLowBandGain * micAlcGain
        val startHighGain = micHighBandGain * micAlcGain

        micLowBandGain = if (targetLowGain > micLowBandGain) {
            micLowBandGain + (targetLowGain - micLowBandGain) * 0.88f
        } else {
            micLowBandGain + (targetLowGain - micLowBandGain) * 0.14f
        }
        micHighBandGain = if (targetHighGain > micHighBandGain) {
            micHighBandGain + (targetHighGain - micHighBandGain) * 0.90f
        } else {
            micHighBandGain + (targetHighGain - micHighBandGain) * 0.15f
        }

        val endLowGain = micLowBandGain * micAlcGain
        val endHighGain = micHighBandGain * micAlcGain

        val lowStep = (endLowGain - startLowGain) / sampleCount.toDouble()
        val highStep = (endHighGain - startHighGain) / sampleCount.toDouble()
        var curLowGain = startLowGain.toDouble()
        var curHighGain = startHighGain.toDouble()

        for (i in 0 until sampleCount) {
            curLowGain += lowStep
            curHighGain += highStep
            var out = micLowScratch[i] * curLowGain + micHighScratch[i] * curHighGain

            // Transparent soft-knee peak limiter (prevents G.711 clipping crackle)
            if (out > 26000.0) {
                out = 26000.0 + 6000.0 * tanh((out - 26000.0) / 5000.0)
            } else if (out < -26000.0) {
                out = -26000.0 - 6000.0 * tanh((-out - 26000.0) / 5000.0)
            }

            val outShort = out.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val byteIndex = offset + i * 2
            bytes[byteIndex] = (outShort and 0xFF).toByte()
            bytes[byteIndex + 1] = ((outShort shr 8) and 0xFF).toByte()
        }

        return@synchronized isVoiceActive
    }

    /**
     * Speaker path: clean 120 Hz - 3200 Hz telephony shaping, zero noise-floor amplification,
     * gentle idle-hiss softening, and earpiece anti-distortion soft limiting.
     */
    fun processIncomingPcm(bytes: ByteArray, offset: Int, length: Int) = synchronized(spkLock) {
        val safeLen = minOf(length, bytes.size - offset)
        val sampleCount = if (offset >= 0 && safeLen > 0) safeLen / 2 else 0
        if (sampleCount <= 0) return@synchronized
        if (spkScratch.size < sampleCount) {
            spkScratch = DoubleArray(sampleCount)
        }

        // 1. Apply 120 Hz HPF + smooth 3200 Hz LPF into unclipped Double scratch buffer
        //    so Pass 2 ALC attenuation & soft-knee limiter run BEFORE 16-bit quantization!
        var sumSq = 0.0
        for (i in 0 until sampleCount) {
            val byteIndex = offset + i * 2
            val low = bytes[byteIndex].toInt() and 0xFF
            val high = bytes[byteIndex + 1].toInt()
            val inSample = ((high shl 8) or low).toShort().toDouble()

            var filtered = spkHpf.process(inSample)
            filtered = spkLpf.process(filtered)

            spkScratch[i] = filtered
            sumSq += filtered * filtered
        }
        val frameRms = sqrt(sumSq / sampleCount)
        lastIncomingRms = frameRms

        // 2. Speech-Only Leveler (NEVER boosts quiet remote line hiss!)
        if (frameRms > 900.0) {
            val desiredGain = when {
                frameRms < 1800.0 -> (TARGET_VOICE_RMS / frameRms).toFloat().coerceIn(1.0f, 1.15f)
                frameRms > 6500.0 -> (TARGET_VOICE_RMS / frameRms).toFloat().coerceIn(0.65f, 1.0f)
                else -> 1.0f
            }
            spkAlcGain += (desiredGain - spkAlcGain) * 0.02f
        }

        // 3. Gentle PSTN idle-line hiss softener (only engages after 260 ms of pure quiet floor < 160 RMS)
        if (frameRms > 180.0) {
            spkIdleFrames = 0
        } else {
            spkIdleFrames = (spkIdleFrames + 1).coerceAtMost(50)
        }
        val targetGate = if (spkIdleFrames >= 13) SPK_IDLE_FLOOR else 1.0f
        val startGain = spkGateGain * spkAlcGain
        spkGateGain = if (targetGate > spkGateGain) {
            spkGateGain + (targetGate - spkGateGain) * 0.92f
        } else {
            spkGateGain + (targetGate - spkGateGain) * 0.12f
        }
        val endGain = spkGateGain * spkAlcGain
        val gainStep = (endGain - startGain) / sampleCount.toDouble()
        var currentGain = startGain.toDouble()

        // 4. Apply interpolated gain & soft peak limiter on unclipped floating-point signal
        for (i in 0 until sampleCount) {
            currentGain += gainStep
            var out = spkScratch[i] * currentGain

            if (out > 25000.0) {
                out = 25000.0 + (31500.0 - 25000.0) * tanh((out - 25000.0) / 5000.0)
            } else if (out < -25000.0) {
                out = -25000.0 - (31500.0 - 25000.0) * tanh((-out - 25000.0) / 5000.0)
            }

            val outShort = out.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val byteIndex = offset + i * 2
            bytes[byteIndex] = (outShort and 0xFF).toByte()
            bytes[byteIndex + 1] = ((outShort shr 8) and 0xFF).toByte()
        }
    }
}
