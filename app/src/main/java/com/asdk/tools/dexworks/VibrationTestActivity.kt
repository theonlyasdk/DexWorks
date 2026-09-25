package com.asdk.tools.dexworks

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.asdk.tools.dexworks.databinding.ActivityVibrationTestBinding
import com.google.android.material.slider.Slider

class VibrationTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVibrationTestBinding
    private var vibrator: Vibrator? = null
    private var hasAmplitude: Boolean = false
    private var hasPrimitives: Boolean = false

    private val stepDurationIds = listOf(
        R.id.slider_step_1_duration,
        R.id.slider_step_2_duration,
        R.id.slider_step_3_duration,
        R.id.slider_step_4_duration
    )
    private val stepAmplitudeIds = listOf(
        R.id.slider_step_1_amplitude,
        R.id.slider_step_2_amplitude,
        R.id.slider_step_3_amplitude,
        R.id.slider_step_4_amplitude
    )
    private val stepLabelIds = listOf(
        R.id.label_step_1_duration,
        R.id.label_step_2_duration,
        R.id.label_step_3_duration,
        R.id.label_step_4_duration
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVibrationTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        vibrator = Haptics.vibrator(this)
        val present = Haptics.hasVibrator(vibrator)
        hasAmplitude = Haptics.supportsAmplitude(vibrator)
        hasPrimitives = Haptics.supportsPrimitives(vibrator)

        binding.textCapability.text = if (present) {
            getString(
                R.string.vibration_capability_full,
                getString(if (hasAmplitude) R.string.vibration_yes else R.string.vibration_no),
                getString(if (hasPrimitives) R.string.vibration_yes else R.string.vibration_no)
            )
        } else {
            getString(R.string.vibration_capability_none)
        }

        if (!hasAmplitude) {
            binding.sliderSingleAmplitude.isEnabled = false
            binding.labelSingleAmplitude.setText(R.string.vibration_amplitude_off)
            stepAmplitudeIds.forEach { binding.root.findViewById<Slider>(it).isEnabled = false }
        }

        wireSingle()
        wirePattern()
        wireTests()
    }

    private fun wireSingle() {
        binding.sliderSingleDuration.addOnChangeListener { _, value, _ ->
            binding.labelSingleDuration.text =
                getString(R.string.vibration_duration, value.toInt())
        }
        binding.labelSingleDuration.text =
            getString(R.string.vibration_duration, binding.sliderSingleDuration.value.toInt())

        binding.sliderSingleAmplitude.addOnChangeListener { _, value, _ ->
            binding.labelSingleAmplitude.text =
                getString(R.string.vibration_amplitude, value.toInt())
        }
        if (hasAmplitude) {
            binding.labelSingleAmplitude.text =
                getString(R.string.vibration_amplitude, binding.sliderSingleAmplitude.value.toInt())
        }

        binding.btnVibrate.setOnClickListener {
            Haptics.playOneShot(
                vibrator,
                binding.sliderSingleDuration.value.toLong(),
                binding.sliderSingleAmplitude.value.toInt()
            )
        }
    }

    private fun wirePattern() {
        stepDurationIds.forEachIndexed { index, id ->
            val slider = binding.root.findViewById<Slider>(id)
            val label = binding.root.findViewById<android.widget.TextView>(stepLabelIds[index])
            slider.addOnChangeListener { _, value, _ ->
                label.text = getString(R.string.vibration_step, index + 1, value.toInt())
                updatePatternPreview()
            }
            label.text = getString(R.string.vibration_step, index + 1, slider.value.toInt())
        }

        binding.switchRepeat.setOnCheckedChangeListener { _, _ -> updatePatternPreview() }
        updatePatternPreview()

        binding.btnRunPattern.setOnClickListener { runPattern() }
    }

    private fun currentStepDurations(): List<Int> =
        stepDurationIds.map { binding.root.findViewById<Slider>(it).value.toInt() }

    private fun currentStepAmplitudes(): List<Int> =
        stepAmplitudeIds.map { binding.root.findViewById<Slider>(it).value.toInt() }

    private fun runPattern() {
        val durations = currentStepDurations()
        val amplitudes = currentStepAmplitudes()

        // A waveform alternates on/off, so each step becomes an "on" followed by a
        // gap. Trailing and zero-length steps are dropped, and a zero step becomes
        // a pure pause, which is how you build rhythms.
        val timings = mutableListOf<Long>()
        val amps = mutableListOf<Int>()
        val gap = 60L
        var lastOnIndex = -1
        durations.forEachIndexed { index, duration ->
            if (duration > 0) lastOnIndex = index
        }
        for (index in 0..lastOnIndex) {
            timings += durations[index].toLong()
            amps += if (durations[index] > 0) amplitudes[index] else 0
            timings += gap
            amps += 0
        }

        if (timings.isEmpty() || lastOnIndex < 0) {
            Haptics.playOneShot(vibrator, 10L, 0)
            return
        }

        val repeat = if (binding.switchRepeat.isChecked) 0 else -1
        Haptics.playWaveform(
            vibrator,
            timings.toLongArray(),
            amps.toIntArray(),
            repeat
        )
    }

    private fun updatePatternPreview() {
        val durations = currentStepDurations()
        val amps = currentStepAmplitudes()
        val timings = mutableListOf<Long>()
        val values = mutableListOf<Int>()
        var lastOnIndex = -1
        durations.forEachIndexed { index, duration ->
            if (duration > 0) lastOnIndex = index
        }
        for (index in 0..lastOnIndex) {
            timings += durations[index].toLong()
            values += if (durations[index] > 0) amps[index] else 0
            timings += 60L
            values += 0
        }
        val repeat = if (binding.switchRepeat.isChecked) 0 else -1
        binding.textPatternPreview.text = if (timings.isEmpty()) {
            ""
        } else {
            getString(
                R.string.vibration_preview,
                timings.toString(),
                values.toString(),
                repeat
            )
        }
    }

    private fun wireTests() {
        // Test 1: two crisp taps, the classic double tap confirmation.
        binding.btnTest1.setOnClickListener {
            if (hasPrimitives) {
                Haptics.playPrimitive(
                    vibrator,
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    0L
                )
                binding.root.postDelayed({
                    Haptics.playPrimitive(
                        vibrator,
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        0L
                    )
                }, 90L)
            } else {
                Haptics.playWaveform(
                    vibrator,
                    longArrayOf(0, 18, 72, 18),
                    intArrayOf(0, 255, 0, 255),
                    -1
                )
            }
        }

        // Test 2: O-Haptics style. O-Haptics grades a tap across three discrete
        // strengths rather than a float scale, and on an X-axis linear actuator the
        // tap has to be a short factory impulse or it smears. A composition
        // primitive is the closest AOSP equivalent; without one we fall back to a
        // very short high-amplitude pulse, which reads as a tick instead of a buzz.
        binding.btnTest2.setOnClickListener {
            if (hasPrimitives) {
                Haptics.playPrimitive(
                    vibrator,
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    0L
                )
            } else if (hasAmplitude) {
                Haptics.playOneShot(vibrator, 12L, 255)
            } else {
                Haptics.playOneShot(vibrator, 20L, 0)
            }
        }

        // Test 3: heartbeat, a heavier thud followed by a lighter tick.
        binding.btnTest3.setOnClickListener {
            if (hasPrimitives) {
                Haptics.playPrimitive(
                    vibrator,
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    0L
                )
                binding.root.postDelayed({
                    Haptics.playPrimitive(
                        vibrator,
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        0L
                    )
                }, 140L)
            } else {
                Haptics.playWaveform(
                    vibrator,
                    longArrayOf(0, 45, 95, 22),
                    intArrayOf(0, 255, 0, 170),
                    -1
                )
            }
        }

        binding.btnStop.setOnClickListener { Haptics.cancel(vibrator) }
    }

    override fun onStop() {
        super.onStop()
        Haptics.cancel(vibrator)
    }
}
