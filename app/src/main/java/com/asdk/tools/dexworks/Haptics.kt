package com.asdk.tools.dexworks

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Thin wrapper over the vibration APIs, covering the three generations of the API:
 * the pre-Oreo waveform arrays, Oreo one-shot/waveform effects, and the Android 11
 * composition primitives.
 */
object Haptics {

    fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun hasVibrator(vibrator: Vibrator?): Boolean = vibrator?.hasVibrator() == true

    fun supportsAmplitude(vibrator: Vibrator?): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            vibrator?.hasAmplitudeControl() == true
    }

    /** True when the platform can play factory-tuned composition primitives (Android 11+). */
    fun supportsPrimitives(vibrator: Vibrator?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || vibrator == null) return false
        return vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD
        )
    }

    fun playOneShot(vibrator: Vibrator?, durationMs: Long, amplitude: Int) {
        val target = vibrator ?: return
        if (durationMs <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amp = if (target.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            target.vibrate(VibrationEffect.createOneShot(durationMs, amp))
        } else {
            @Suppress("DEPRECATION")
            target.vibrate(durationMs)
        }
    }

    fun playWaveform(
        vibrator: Vibrator?,
        timings: LongArray,
        amplitudes: IntArray,
        repeat: Int
    ) {
        val target = vibrator ?: return
        if (timings.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val safeAmplitudes = if (target.hasAmplitudeControl()) {
                amplitudes
            } else {
                IntArray(timings.size) { VibrationEffect.DEFAULT_AMPLITUDE }
            }
            target.vibrate(VibrationEffect.createWaveform(timings, safeAmplitudes, repeat))
        } else {
            @Suppress("DEPRECATION")
            target.vibrate(timings, repeat)
        }
    }

    /**
     * Plays a composition primitive, which is a factory-tuned impulse. On an X-axis
     * linear actuator this is what makes a tap read as crisp instead of smearing,
     * which is the character of OPPO's O-Haptics "click".
     *
     * [delayMs] is in milliseconds and is converted to the seconds the API expects.
     */
    fun playPrimitive(vibrator: Vibrator?, primitive: Int, delayMs: Long) {
        val target = vibrator ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!target.areAllPrimitivesSupported(primitive)) return
        val effect = VibrationEffect.startComposition()
            .addPrimitive(primitive, delayMs.coerceAtLeast(0L) / 1000f)
            .compose()
        target.vibrate(effect)
    }

    fun cancel(vibrator: Vibrator?) {
        vibrator?.cancel()
    }
}
