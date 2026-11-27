package shop.terramoor.github.io.systemui

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.hypot

/**
 * Lightweight real-time vocal-band (300 Hz - 3.4 kHz) energy tracker backed by Android's
 * system output mix [Visualizer]. Strictly opt-in and only kept active while the screen is on,
 * playback is running, and the current track uses interpolated (non-word-synced) lyrics.
 *
 * Falls back silently to a no-op if the ROM denies Visualizer access.
 */
internal class VocalEnergyTracker {
    private var visualizer: Visualizer? = null
    private var failed = false
    @Volatile
    private var active = false
    @Volatile
    private var smoothedEnergy = 0.5f
    private var adaptivePeak = 18f

    private var trackedLineStart = Long.MIN_VALUE
    private var lastUpdateMs = 0L
    private var pacedOffsetMs = 0f

    val isAvailable: Boolean get() = !failed && visualizer != null

    fun setActive(shouldBeActive: Boolean) {
        if (active == shouldBeActive) return
        active = shouldBeActive
        if (!shouldBeActive) {
            release()
            return
        }
        if (failed) return
        start()
    }

    private fun start() {
        if (visualizer != null || failed) return
        runCatching {
            val v = Visualizer(0)
            v.enabled = false
            val range = Visualizer.getCaptureSizeRange()
            val desiredSize = 256.coerceIn(range[0], range[1])
            v.captureSize = desiredSize
            val maxRate = Visualizer.getMaxCaptureRate()
            val rate = minOf(15_000, maxRate)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) onFft(fft)
                    }
                },
                rate,
                false,
                true,
            )
            v.enabled = true
            visualizer = v
            Log.i(TAG, "VocalEnergyTracker started (size=$desiredSize, rate=${rate}mHz)")
        }.onFailure { err ->
            failed = true
            release()
            Log.w(TAG, "VocalEnergyTracker unavailable, using pure syllable interpolation: ${err.message}")
        }
    }

    private fun onFft(fft: ByteArray) {
        // For 256-point FFT at 44.1/48 kHz, each bin is ~172-187 Hz.
        // Bins 2..18 cover ~340 Hz to ~3.3 kHz (primary human vocal formants).
        val n = fft.size / 2
        val startBin = 2.coerceAtMost(n - 1)
        val endBin = 18.coerceAtMost(n - 1)
        if (endBin <= startBin) return
        var sum = 0f
        var count = 0
        for (bin in startBin..endBin) {
            val re = fft[2 * bin].toFloat()
            val im = fft[2 * bin + 1].toFloat()
            sum += hypot(re, im)
            count++
        }
        val avg = if (count > 0) sum / count else 0f
        adaptivePeak = maxOf(adaptivePeak * 0.985f, avg, 12f)
        val normalized = (avg / adaptivePeak).coerceIn(0f, 1f)
        smoothedEnergy = smoothedEnergy * 0.68f + normalized * 0.32f
    }

    /**
     * Modulates [rawPosMs] gently within [lineStartMs]..[lineEndMs] based on real-time vocal energy:
     * slows down slightly during breaths/silence and catches up during strong vocal syllables,
     * bounded to ±220 ms so highlight never drifts away from the actual line.
     */
    fun pacedPosition(rawPosMs: Long, lineStartMs: Long, lineEndMs: Long, nowMs: Long): Long {
        if (!active || visualizer == null) return rawPosMs
        if (lineStartMs != trackedLineStart) {
            trackedLineStart = lineStartMs
            lastUpdateMs = nowMs
            pacedOffsetMs = 0f
            return rawPosMs
        }
        val dt = (nowMs - lastUpdateMs).coerceIn(0L, 120L)
        lastUpdateMs = nowMs
        if (dt == 0L) return (rawPosMs + pacedOffsetMs.toLong()).coerceIn(lineStartMs, lineEndMs)

        val energy = smoothedEnergy
        val rateBias = when {
            energy < 0.18f -> -0.18f
            energy > 0.42f -> (energy - 0.42f) * 0.28f
            else -> 0f
        }
        val springPull = -pacedOffsetMs * 0.005f
        pacedOffsetMs = (pacedOffsetMs + (rateBias + springPull) * dt).coerceIn(-75f, 75f)
        return (rawPosMs + pacedOffsetMs.toLong()).coerceIn(lineStartMs, lineEndMs)
    }

    fun release() {
        val v = visualizer
        visualizer = null
        trackedLineStart = Long.MIN_VALUE
        pacedOffsetMs = 0f
        if (v != null) {
            runCatching { v.enabled = false }
            runCatching { v.release() }
        }
    }
}
