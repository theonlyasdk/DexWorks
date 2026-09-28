package com.asdk.tools.dexworks

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.asdk.tools.dexworks.databinding.ActivityVibrationTestBinding
import com.asdk.tools.dexworks.databinding.ItemVibrationPointBinding
import com.asdk.tools.dexworks.databinding.ItemVibrationPresetBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VibrationTestActivity : AppCompatActivity() {

    enum class WaveformMode {
        DISCRETE, SINE, SQUARE, SAWTOOTH, TRIANGLE, CHIRP
    }

    enum class LfoShape {
        RAMP_UP, SINE
    }

    data class EditablePoint(
        var durationMs: Long,
        var amplitude: Int
    )

    private lateinit var binding: ActivityVibrationTestBinding
    private var vibrator: Vibrator? = null
    private var hasAmplitude: Boolean = false
    private var hasPrimitives: Boolean = false

    private var activePlaybackJob: Job? = null
    private var isPlaying: Boolean = false
    private var isLoopingMode: Boolean = false
    private var activePresetTitleRes: Int? = null
    private val presetBindings = mutableListOf<Pair<PresetItem, ItemVibrationPresetBinding>>()

    private var isVoiceListening: Boolean = false
    private var audioRecord: AudioRecord? = null
    private var voiceListeningJob: Job? = null
    private var voiceSensitivity: Int = 3

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startVoiceListening()
        } else {
            Toast.makeText(this, R.string.vibration_voice_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private var currentMode: WaveformMode = WaveformMode.DISCRETE
    private val discretePoints = mutableListOf<EditablePoint>()

    private var isLfoEnabled: Boolean = false
    private var lfoShape: LfoShape = LfoShape.RAMP_UP
    private var currentActiveWaveform: GeneratedWaveform? = null
    private var lastHapticDispatchMs = 0L
    private val hapticHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingHapticDispatch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVibrationTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdgeWithPadding(binding.layoutMainContent)
        val bottomSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(binding.bottomSheetVisualizer)
        bottomSheetBehavior.isHideable = false
        bottomSheetBehavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED

        fun updateScrollContentPadding(sheet: android.view.View) {
            val rootH = binding.root.height
            if (rootH <= 0) return
            val coveredH = (rootH - sheet.top).coerceAtLeast(0)
            val basePadding = 16.dp()
            binding.containerScrollContent.setPadding(
                binding.containerScrollContent.paddingLeft,
                binding.containerScrollContent.paddingTop,
                binding.containerScrollContent.paddingRight,
                coveredH + basePadding
            )
        }

        bottomSheetBehavior.addBottomSheetCallback(object : com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: android.view.View, newState: Int) {
                updateScrollContentPadding(bottomSheet)
            }

            override fun onSlide(bottomSheet: android.view.View, slideOffset: Float) {
                updateScrollContentPadding(bottomSheet)
            }
        })

        // bottomSheetVisualizer insets handled by enableEdgeToEdgeWithPadding on layoutMainContent
        // bottomSheetBehavior.peekHeight adjusted in updateScrollContentPadding

        binding.toolbar.setNavigationOnClickListener { finish() }

        vibrator = Haptics.vibrator(this)
        val present = Haptics.hasVibrator(vibrator)
        hasAmplitude = Haptics.supportsAmplitude(vibrator)
        hasPrimitives = Haptics.supportsPrimitives(vibrator)

        val colorPrimary = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#00BCD4"))
        val colorError = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError, Color.parseColor("#BA1A1A"))

        binding.textMotorStatus.text = getString(
            if (present) R.string.vibration_status_available else R.string.vibration_status_unavailable
        )
        binding.iconMotorStatus.setImageResource(
            if (present) R.drawable.ic_check_circle_rounded else R.drawable.ic_cancel_circle_rounded
        )
        binding.iconMotorStatus.imageTintList = ColorStateList.valueOf(if (present) colorPrimary else colorError)

        binding.textAmplitudeStatus.text = getString(
            if (hasAmplitude) R.string.vibration_supported else R.string.vibration_unsupported
        )
        binding.iconAmplitudeStatus.setImageResource(
            if (hasAmplitude) R.drawable.ic_check_circle_rounded else R.drawable.ic_cancel_circle_rounded
        )
        binding.iconAmplitudeStatus.imageTintList = ColorStateList.valueOf(if (hasAmplitude) colorPrimary else colorError)

        binding.textPrimitivesStatus.text = getString(
            if (hasPrimitives) R.string.vibration_supported else R.string.vibration_unsupported
        )
        binding.iconPrimitivesStatus.setImageResource(
            if (hasPrimitives) R.drawable.ic_check_circle_rounded else R.drawable.ic_cancel_circle_rounded
        )
        binding.iconPrimitivesStatus.imageTintList = ColorStateList.valueOf(if (hasPrimitives) colorPrimary else colorError)

        if (!hasAmplitude) {
            binding.sliderSingleAmplitude.isEnabled = false
            binding.labelSingleAmplitude.setText(R.string.vibration_amplitude_off)
            binding.sliderWaveAmp.isEnabled = false
            binding.labelWaveAmp.setText(R.string.vibration_amplitude_off)
        }

        initDefaultDiscretePoints()
        wireModeSelector()
        wireContinuousSliders()
        wireLfoControls()
        wireDiscreteControls()
        wirePlaybackButtons()
        wireSingle()
        wireTests()
        wireVoiceResponsive()

        setPlaybackUiState(playing = false, looping = false)
        updateWaveformGraphAndPreview()
    }

    private fun initDefaultDiscretePoints() {
        discretePoints.clear()
        discretePoints += EditablePoint(80L, 220)
        discretePoints += EditablePoint(80L, 200)
        discretePoints += EditablePoint(120L, 255)
    }

    private fun wireModeSelector() {
        binding.toggleWaveformMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = when (checkedId) {
                    R.id.btn_mode_sine -> WaveformMode.SINE
                    R.id.btn_mode_square -> WaveformMode.SQUARE
                    R.id.btn_mode_sawtooth -> WaveformMode.SAWTOOTH
                    R.id.btn_mode_triangle -> WaveformMode.TRIANGLE
                    R.id.btn_mode_chirp -> WaveformMode.CHIRP
                    else -> WaveformMode.DISCRETE
                }

                when (currentMode) {
                    WaveformMode.DISCRETE -> {
                        binding.layoutDiscreteMode.isVisible = true
                        binding.layoutContinuousMode.isVisible = false
                    }
                    WaveformMode.SINE, WaveformMode.SAWTOOTH, WaveformMode.TRIANGLE -> {
                        binding.layoutDiscreteMode.isVisible = false
                        binding.layoutContinuousMode.isVisible = true
                        binding.layoutDutyCycle.isVisible = false
                        binding.layoutChirpEnd.isVisible = false
                    }
                    WaveformMode.SQUARE -> {
                        binding.layoutDiscreteMode.isVisible = false
                        binding.layoutContinuousMode.isVisible = true
                        binding.layoutDutyCycle.isVisible = true
                        binding.layoutChirpEnd.isVisible = false
                    }
                    WaveformMode.CHIRP -> {
                        binding.layoutDiscreteMode.isVisible = false
                        binding.layoutContinuousMode.isVisible = true
                        binding.layoutDutyCycle.isVisible = false
                        binding.layoutChirpEnd.isVisible = true
                    }
                }
                updateWaveformGraphAndPreview()
            }
        }
    }

    private fun formatDuration(durationMs: Float): String {
        return if (durationMs >= 1000f) {
            getString(R.string.vibration_total_duration_sec, durationMs / 1000f)
        } else {
            getString(R.string.vibration_total_duration, durationMs.toInt())
        }
    }

    private fun wireContinuousSliders() {
        binding.sliderWaveAmp.addOnChangeListener { _, value, _ ->
            if (hasAmplitude) {
                binding.labelWaveAmp.text = getString(R.string.vibration_amplitude, value.toInt())
            }
            updateWaveformGraphAndPreview()
        }
        if (hasAmplitude) {
            binding.labelWaveAmp.text =
                getString(R.string.vibration_amplitude, binding.sliderWaveAmp.value.toInt())
        }

        binding.sliderWaveFreq.addOnChangeListener { _, value, _ ->
            binding.labelWaveFreq.text = getString(R.string.vibration_frequency, value.toInt())
            updateWaveformGraphAndPreview()
        }
        binding.labelWaveFreq.text =
            getString(R.string.vibration_frequency, binding.sliderWaveFreq.value.toInt())

        binding.sliderWaveDuration.addOnChangeListener { _, value, _ ->
            binding.labelWaveDuration.text = formatDuration(value)
            updateWaveformGraphAndPreview()
        }
        binding.labelWaveDuration.text = formatDuration(binding.sliderWaveDuration.value)

        binding.sliderWaveDuty.addOnChangeListener { _, value, _ ->
            binding.labelWaveDuty.text = getString(R.string.vibration_duty_cycle, value.toInt())
            updateWaveformGraphAndPreview()
        }
        binding.labelWaveDuty.text =
            getString(R.string.vibration_duty_cycle, binding.sliderWaveDuty.value.toInt())

        binding.sliderChirpEnd.addOnChangeListener { _, value, _ ->
            binding.labelChirpEnd.text = getString(R.string.vibration_chirp_end_freq, value.toInt())
            updateWaveformGraphAndPreview()
        }
        binding.labelChirpEnd.text =
            getString(R.string.vibration_chirp_end_freq, binding.sliderChirpEnd.value.toInt())
    }

    private fun wireLfoControls() {
        binding.switchLfo.setOnCheckedChangeListener { _, isChecked ->
            isLfoEnabled = isChecked
            binding.layoutLfoControls.isVisible = isChecked
            updateWaveformGraphAndPreview()
        }

        binding.toggleLfoShape.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                lfoShape = if (checkedId == R.id.btn_lfo_sine) LfoShape.SINE else LfoShape.RAMP_UP
                updateWaveformGraphAndPreview()
            }
        }

        binding.sliderLfoDepth.addOnChangeListener { _, value, _ ->
            binding.labelLfoDepth.text = getString(R.string.vibration_lfo_depth, value.toInt())
            updateWaveformGraphAndPreview()
        }
        binding.labelLfoDepth.text =
            getString(R.string.vibration_lfo_depth, binding.sliderLfoDepth.value.toInt())

        binding.sliderLfoRate.addOnChangeListener { _, value, _ ->
            binding.labelLfoRate.text = getString(R.string.vibration_lfo_rate, value)
            updateWaveformGraphAndPreview()
        }
        binding.labelLfoRate.text =
            getString(R.string.vibration_lfo_rate, binding.sliderLfoRate.value)
    }

    private fun createPointTransition(): TransitionSet = TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(Fade())
        addTransition(ChangeBounds())
        duration = 200L
    }

    private fun wireDiscreteControls() {
        binding.btnAddPoint.setOnClickListener {
            TransitionManager.beginDelayedTransition(binding.containerPoints, createPointTransition())
            addDiscretePoint(EditablePoint(100L, 200))
        }
        renderDiscretePointViews()
    }

    private fun addDiscretePoint(point: EditablePoint) {
        val index = discretePoints.size
        discretePoints += point
        val itemBinding = ItemVibrationPointBinding.inflate(layoutInflater, binding.containerPoints, false)
        val view = itemBinding.root

        itemBinding.textPointTitle.text = getString(R.string.vibration_step_title, index + 1)
        itemBinding.labelPointDuration.text = getString(R.string.vibration_duration, point.durationMs.toInt())
        itemBinding.sliderPointDuration.value = point.durationMs.toFloat().coerceIn(10f, 1000f)
        itemBinding.sliderPointDuration.addOnChangeListener { _, value, _ ->
            point.durationMs = value.toLong()
            itemBinding.labelPointDuration.text = getString(R.string.vibration_duration, value.toInt())
            updateWaveformGraphAndPreview()
        }

        if (hasAmplitude) {
            itemBinding.labelPointAmplitude.text = getString(R.string.vibration_amplitude, point.amplitude)
            itemBinding.sliderPointAmplitude.value = point.amplitude.toFloat().coerceIn(0f, 255f)
            itemBinding.sliderPointAmplitude.addOnChangeListener { _, value, _ ->
                point.amplitude = value.toInt()
                itemBinding.labelPointAmplitude.text = getString(R.string.vibration_amplitude, value.toInt())
                updateWaveformGraphAndPreview()
            }
        } else {
            itemBinding.sliderPointAmplitude.isEnabled = false
            itemBinding.labelPointAmplitude.setText(R.string.vibration_amplitude_off)
        }

        itemBinding.btnDeletePoint.setOnClickListener {
            val currentIndex = binding.containerPoints.indexOfChild(view)
            if (currentIndex != -1) {
                removeDiscretePoint(currentIndex)
            }
        }

        binding.containerPoints.addView(view)
        updatePointIndices()
        updateWaveformGraphAndPreview()
    }

    private fun removeDiscretePoint(index: Int) {
        if (discretePoints.size <= 1 || index !in discretePoints.indices) return
        TransitionManager.beginDelayedTransition(binding.containerPoints, createPointTransition())
        discretePoints.removeAt(index)
        binding.containerPoints.removeViewAt(index)
        updatePointIndices()
        updateWaveformGraphAndPreview()
    }

    private fun updatePointIndices() {
        binding.textPointsCount.text = getString(R.string.vibration_points_count, discretePoints.size)
        for (i in 0 until binding.containerPoints.childCount) {
            val child = binding.containerPoints.getChildAt(i)
            val titleView = child.findViewById<android.widget.TextView>(R.id.text_point_title)
            titleView?.text = getString(R.string.vibration_step_title, i + 1)
            val deleteBtn = child.findViewById<android.view.View>(R.id.btn_delete_point)
            deleteBtn?.isEnabled = discretePoints.size > 1
        }
    }

    private fun renderDiscretePointViews() {
        binding.containerPoints.removeAllViews()
        val currentList = discretePoints.toList()
        discretePoints.clear()
        currentList.forEach { addDiscretePoint(it) }
    }

    private data class GeneratedWaveform(
        val graphPoints: List<VibrationGraphView.VibrationPoint>,
        val isContinuous: Boolean,
        val timings: LongArray,
        val amplitudes: IntArray,
        val totalTimeMs: Long
    )

    private fun generateCurrentWaveform(): GeneratedWaveform {
        val timings = mutableListOf<Long>()
        val amps = mutableListOf<Int>()
        val graphPoints = mutableListOf<VibrationGraphView.VibrationPoint>()

        when (currentMode) {
            WaveformMode.DISCRETE -> {
                var currentTime = 0f
                val gap = 60f
                discretePoints.forEachIndexed { index, pt ->
                    val dur = pt.durationMs.toFloat()
                    val amp = if (dur > 0) pt.amplitude.toFloat() else 0f
                    timings += pt.durationMs
                    amps += pt.amplitude

                    if (dur > 0) {
                        graphPoints += VibrationGraphView.VibrationPoint(
                            stepIndex = index,
                            timeMs = currentTime,
                            amplitude = amp,
                            durationMs = dur
                        )
                    }
                    currentTime += dur + gap
                    if (index < discretePoints.size - 1) {
                        timings += 60L
                        amps += 0
                    }
                }
                val totalTime = timings.sum()
                return GeneratedWaveform(graphPoints, false, timings.toLongArray(), amps.toIntArray(), totalTime)
            }
            WaveformMode.SINE, WaveformMode.SQUARE, WaveformMode.SAWTOOTH, WaveformMode.TRIANGLE, WaveformMode.CHIRP -> {
                val baseAmp = binding.sliderWaveAmp.value.toInt()
                val baseFreq = binding.sliderWaveFreq.value
                val totalDuration = binding.sliderWaveDuration.value.toLong().coerceIn(500L, 30000L)
                val totalSec = totalDuration / 1000.0

                val dutyFraction = (binding.sliderWaveDuty.value / 100.0).coerceIn(0.1, 0.9)
                val lfoDepth = binding.sliderLfoDepth.value
                val lfoRate = binding.sliderLfoRate.value

                val startFreq = binding.sliderWaveFreq.value
                val endFreq = binding.sliderChirpEnd.value

                // 1. High-resolution visual curve evaluation (300 - 600 points)
                val visualPointsCount = (totalDuration / 50L).coerceIn(300L, 600L).toInt()
                val visualStepSec = totalSec / visualPointsCount

                var visualPhase = 0.0
                for (i in 0..visualPointsCount) {
                    val tSec = i * visualStepSec
                    val instFreq = if (isLfoEnabled) {
                        when (lfoShape) {
                            LfoShape.RAMP_UP -> baseFreq + lfoDepth * (tSec / totalSec).coerceIn(0.0, 1.0).toFloat()
                            LfoShape.SINE -> baseFreq + lfoDepth * (0.5 * (1.0 + sin(2.0 * Math.PI * lfoRate * tSec))).toFloat()
                        }
                    } else {
                        baseFreq
                    }

                    visualPhase += 2.0 * Math.PI * instFreq * visualStepSec
                    val normPhase = ((visualPhase / (2.0 * Math.PI)) % 1.0 + 1.0) % 1.0

                    val factor: Float = when (currentMode) {
                        WaveformMode.SINE -> (0.5 * (1.0 + sin(visualPhase))).toFloat()
                        WaveformMode.SQUARE -> if (normPhase < dutyFraction) 1.0f else 0.0f
                        WaveformMode.SAWTOOTH -> normPhase.toFloat()
                        WaveformMode.TRIANGLE -> (1.0 - 2.0 * abs(normPhase - 0.5)).toFloat().coerceIn(0f, 1f)
                        WaveformMode.CHIRP -> {
                            val prog = (i.toFloat() / visualPointsCount).coerceIn(0f, 1f)
                            val chirpFreq = startFreq + (endFreq - startFreq) * prog
                            (0.5 * (1.0 + sin(2.0 * Math.PI * chirpFreq * tSec))).toFloat()
                        }
                        else -> 1.0f
                    }

                    val amp = (baseAmp * factor).toInt().coerceIn(0, 255)
                    graphPoints += VibrationGraphView.VibrationPoint(
                        stepIndex = i,
                        timeMs = (tSec * 1000.0).toFloat(),
                        amplitude = amp.toFloat(),
                        durationMs = (visualStepSec * 1000.0).toFloat()
                    )
                }

                // 2. Hardware haptic vibration timings (20ms - 50ms slices, max 250 steps)
                val hapticStepMs = (totalDuration / 180L).coerceIn(20L, 50L)
                val hapticSteps = (totalDuration / hapticStepMs).toInt().coerceIn(10, 250)
                var hapticPhase = 0.0
                for (i in 0 until hapticSteps) {
                    val tSec = (i * hapticStepMs) / 1000.0
                    val instFreq = if (isLfoEnabled) {
                        when (lfoShape) {
                            LfoShape.RAMP_UP -> baseFreq + lfoDepth * (tSec / totalSec).coerceIn(0.0, 1.0).toFloat()
                            LfoShape.SINE -> baseFreq + lfoDepth * (0.5 * (1.0 + sin(2.0 * Math.PI * lfoRate * tSec))).toFloat()
                        }
                    } else {
                        baseFreq
                    }

                    hapticPhase += 2.0 * Math.PI * instFreq * (hapticStepMs / 1000.0)
                    val normPhase = ((hapticPhase / (2.0 * Math.PI)) % 1.0 + 1.0) % 1.0

                    val factor: Float = when (currentMode) {
                        WaveformMode.SINE -> (0.5 * (1.0 + sin(hapticPhase))).toFloat()
                        WaveformMode.SQUARE -> if (normPhase < dutyFraction) 1.0f else 0.0f
                        WaveformMode.SAWTOOTH -> normPhase.toFloat()
                        WaveformMode.TRIANGLE -> (1.0 - 2.0 * abs(normPhase - 0.5)).toFloat().coerceIn(0f, 1f)
                        WaveformMode.CHIRP -> {
                            val prog = i.toFloat() / hapticSteps
                            val chirpFreq = startFreq + (endFreq - startFreq) * prog
                            (0.5 * (1.0 + sin(2.0 * Math.PI * chirpFreq * tSec))).toFloat()
                        }
                        else -> 1.0f
                    }

                    val amp = (baseAmp * factor).toInt().coerceIn(0, 255)
                    timings += hapticStepMs
                    amps += amp
                }

                val isContinuous = currentMode != WaveformMode.SQUARE
                return GeneratedWaveform(graphPoints, isContinuous, timings.toLongArray(), amps.toIntArray(), totalDuration)
            }
        }
    }

    private fun updateWaveformGraphAndPreview() {
        val waveform = generateCurrentWaveform()
        binding.graphView.setPattern(waveform.graphPoints, waveform.isContinuous)

        val repeat = if (isLoopingMode) 0 else -1
        binding.textPatternPreview.text = if (waveform.timings.isEmpty()) {
            ""
        } else {
            getString(
                R.string.vibration_preview,
                waveform.timings.take(8).toString() + if (waveform.timings.size > 8) "…" else "",
                waveform.amplitudes.take(8).toString() + if (waveform.amplitudes.size > 8) "…" else "",
                repeat
            )
        }

        if (isPlaying && waveform.timings.isNotEmpty()) {
            currentActiveWaveform = waveform
            val now = android.os.SystemClock.uptimeMillis()
            pendingHapticDispatch?.let { hapticHandler.removeCallbacks(it) }
            val timeSinceLast = now - lastHapticDispatchMs
            if (timeSinceLast >= 75L) {
                lastHapticDispatchMs = now
                Haptics.playWaveform(
                    vibrator,
                    waveform.timings,
                    waveform.amplitudes,
                    repeat
                )
            } else {
                val runnable = Runnable {
                    if (isPlaying && currentActiveWaveform != null) {
                        lastHapticDispatchMs = android.os.SystemClock.uptimeMillis()
                        val wave = currentActiveWaveform ?: return@Runnable
                        val rep = if (isLoopingMode) 0 else -1
                        Haptics.playWaveform(
                            vibrator,
                            wave.timings,
                            wave.amplitudes,
                            rep
                        )
                    }
                }
                pendingHapticDispatch = runnable
                hapticHandler.postDelayed(runnable, 75L - timeSinceLast)
            }
        }
    }

    private fun setPlaybackUiState(playing: Boolean, looping: Boolean) {
        isPlaying = playing
        isLoopingMode = looping

        val colorPrimary = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#00BCD4"))
        val colorOnPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val colorError = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError, Color.parseColor("#BA1A1A"))
        val colorOnError = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnError, Color.WHITE)

        if (playing) {
            if (looping) {
                binding.btnLoopWaveform.setText(R.string.vibration_stop)
                binding.btnLoopWaveform.backgroundTintList = ColorStateList.valueOf(colorError)
                binding.btnLoopWaveform.setTextColor(colorOnError)

                binding.btnPlayWaveform.setText(R.string.vibration_play)
                binding.btnPlayWaveform.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                binding.btnPlayWaveform.setTextColor(colorOnPrimary)
            } else {
                binding.btnPlayWaveform.setText(R.string.vibration_stop)
                binding.btnPlayWaveform.backgroundTintList = ColorStateList.valueOf(colorError)
                binding.btnPlayWaveform.setTextColor(colorOnError)

                binding.btnLoopWaveform.setText(R.string.vibration_loop)
                binding.btnLoopWaveform.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                binding.btnLoopWaveform.setTextColor(colorOnPrimary)
            }
        } else {
            activePresetTitleRes = null
            currentActiveWaveform = null
            binding.graphView.setPlaybackProgress(-1f)

            binding.btnPlayWaveform.setText(R.string.vibration_play)
            binding.btnPlayWaveform.backgroundTintList = ColorStateList.valueOf(colorPrimary)
            binding.btnPlayWaveform.setTextColor(colorOnPrimary)

            binding.btnLoopWaveform.setText(R.string.vibration_loop)
            binding.btnLoopWaveform.backgroundTintList = ColorStateList.valueOf(colorPrimary)
            binding.btnLoopWaveform.setTextColor(colorOnPrimary)
        }
        updatePresetHighlights()
    }

    private fun wirePlaybackButtons() {
        binding.btnPlayWaveform.setOnClickListener {
            if (isPlaying) {
                stopAll()
            } else {
                runWaveform(looping = false)
            }
        }

        binding.btnLoopWaveform.setOnClickListener {
            if (isPlaying) {
                stopAll()
            } else {
                runWaveform(looping = true)
            }
        }
    }

    private fun runWaveform(looping: Boolean) {
        stopAll()
        val waveform = generateCurrentWaveform()
        if (waveform.timings.isEmpty()) return
        currentActiveWaveform = waveform

        val repeat = if (looping) 0 else -1
        Haptics.playWaveform(
            vibrator,
            waveform.timings,
            waveform.amplitudes,
            repeat
        )

        setPlaybackUiState(playing = true, looping = looping)
        activePlaybackJob = lifecycleScope.launch(Dispatchers.Main) {
            var startTime = System.currentTimeMillis()
            while (isActive) {
                val activeWave = currentActiveWaveform ?: break
                val totalTime = activeWave.totalTimeMs.coerceAtLeast(100L)
                val isRepeating = isLoopingMode
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > totalTime) {
                    if (isRepeating) {
                        startTime = System.currentTimeMillis()
                    } else {
                        break
                    }
                }
                binding.graphView.setPlaybackProgress((elapsed % totalTime).toFloat())
                delay(16)
            }
            setPlaybackUiState(playing = false, looping = false)
        }
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
            if (isPlaying) {
                stopAll()
                return@setOnClickListener
            }
            val dur = binding.sliderSingleDuration.value.toLong()
            val amp = binding.sliderSingleAmplitude.value.toInt()
            val point = VibrationGraphView.VibrationPoint(
                stepIndex = 0,
                timeMs = 0f,
                amplitude = amp.toFloat(),
                durationMs = dur.toFloat()
            )
            binding.graphView.setPattern(listOf(point), false)
            Haptics.playOneShot(vibrator, dur, amp)
            animateGraphProgress(dur)
        }
    }

    private data class PresetItem(
        val titleRes: Int,
        val descRes: Int,
        val isLooping: Boolean,
        val action: () -> Unit
    )

    private fun applyExpressiveCorners(card: com.google.android.material.card.MaterialCardView, index: Int, totalCount: Int) {
        val rLarge = 18f * resources.displayMetrics.density
        val rSmall = 4f * resources.displayMetrics.density

        val shapeBuilder = ShapeAppearanceModel.builder()
        when {
            totalCount == 1 -> {
                shapeBuilder.setAllCornerSizes(rLarge)
            }
            index == 0 -> {
                shapeBuilder.setTopLeftCorner(CornerFamily.ROUNDED, rLarge)
                    .setTopRightCorner(CornerFamily.ROUNDED, rLarge)
                    .setBottomLeftCorner(CornerFamily.ROUNDED, rSmall)
                    .setBottomRightCorner(CornerFamily.ROUNDED, rSmall)
            }
            index == totalCount - 1 -> {
                shapeBuilder.setTopLeftCorner(CornerFamily.ROUNDED, rSmall)
                    .setTopRightCorner(CornerFamily.ROUNDED, rSmall)
                    .setBottomLeftCorner(CornerFamily.ROUNDED, rLarge)
                    .setBottomRightCorner(CornerFamily.ROUNDED, rLarge)
            }
            else -> {
                shapeBuilder.setAllCornerSizes(rSmall)
            }
        }
        card.shapeAppearanceModel = shapeBuilder.build()
    }

    private fun updatePresetHighlights() {
        val colorPrimary = MaterialColors.getColor(
            binding.root,
            androidx.appcompat.R.attr.colorPrimary,
            Color.parseColor("#00BCD4")
        )
        val colorSurfaceContainerHigh = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            Color.LTGRAY
        )
        val colorSecondaryContainer = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorSecondaryContainer,
            Color.DKGRAY
        )
        val colorOnSurface = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK
        )
        val colorOnSurfaceVariant = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY
        )
        val colorOnSecondaryContainer = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            Color.WHITE
        )

        for ((item, itemBinding) in presetBindings) {
            val isActive = isPlaying && activePresetTitleRes == item.titleRes
            if (isActive) {
                itemBinding.cardPreset.setCardBackgroundColor(colorSecondaryContainer)
                itemBinding.textPresetTitle.setTextColor(colorOnSecondaryContainer)
                itemBinding.textPresetDesc.setTextColor(colorOnSecondaryContainer)
                itemBinding.iconPreset.setImageResource(R.drawable.ic_check)
                itemBinding.iconPreset.imageTintList = ColorStateList.valueOf(colorOnSecondaryContainer)
            } else {
                itemBinding.cardPreset.setCardBackgroundColor(colorSurfaceContainerHigh)
                itemBinding.textPresetTitle.setTextColor(colorOnSurface)
                itemBinding.textPresetDesc.setTextColor(colorOnSurfaceVariant)
                itemBinding.iconPreset.setImageResource(R.drawable.ic_tool_vibrate)
                itemBinding.iconPreset.imageTintList = ColorStateList.valueOf(colorPrimary)
            }
        }
    }

    private fun populatePresetGroup(
        container: android.view.ViewGroup,
        presets: List<PresetItem>
    ) {
        container.removeAllViews()
        presets.forEachIndexed { index, item ->
            val itemBinding = ItemVibrationPresetBinding.inflate(layoutInflater, container, false)
            itemBinding.textPresetTitle.setText(item.titleRes)
            itemBinding.textPresetDesc.setText(item.descRes)
            applyExpressiveCorners(itemBinding.cardPreset, index, presets.size)

            itemBinding.cardPreset.setOnClickListener {
                item.action()
                activePresetTitleRes = item.titleRes
                updatePresetHighlights()
            }
            container.addView(itemBinding.root)
            presetBindings.add(item to itemBinding)
        }
    }

    private fun buildDiscreteWaveform(points: List<EditablePoint>): GeneratedWaveform {
        val timings = mutableListOf<Long>()
        val amps = mutableListOf<Int>()
        val graphPoints = mutableListOf<VibrationGraphView.VibrationPoint>()
        var currentTime = 0f
        val gap = 60f
        points.forEachIndexed { index, pt ->
            val dur = pt.durationMs.toFloat()
            val amp = if (dur > 0) pt.amplitude.toFloat() else 0f
            timings += pt.durationMs
            amps += pt.amplitude

            if (dur > 0) {
                graphPoints += VibrationGraphView.VibrationPoint(
                    stepIndex = index,
                    timeMs = currentTime,
                    amplitude = amp,
                    durationMs = dur
                )
            }
            currentTime += dur + gap
            if (index < points.size - 1) {
                timings += 60L
                amps += 0
            }
        }
        val totalTime = timings.sum()
        return GeneratedWaveform(graphPoints, false, timings.toLongArray(), amps.toIntArray(), totalTime)
    }

    private fun buildContinuousWaveform(
        mode: WaveformMode,
        freq: Float,
        amp: Int,
        durationMs: Long,
        dutyFraction: Double = 0.5,
        startFreq: Float = freq,
        endFreq: Float = freq
    ): GeneratedWaveform {
        val totalDuration = durationMs.coerceIn(500L, 30000L)
        val totalSec = totalDuration / 1000.0
        val visualPointsCount = (totalDuration / 50L).coerceIn(300L, 600L).toInt()
        val visualStepSec = totalSec / visualPointsCount

        val graphPoints = mutableListOf<VibrationGraphView.VibrationPoint>()
        var visualPhase = 0.0
        for (i in 0..visualPointsCount) {
            val tSec = i * visualStepSec
            val instFreq = if (mode == WaveformMode.CHIRP) {
                val prog = (i.toFloat() / visualPointsCount).coerceIn(0f, 1f)
                startFreq + (endFreq - startFreq) * prog
            } else {
                freq
            }

            visualPhase += 2.0 * Math.PI * instFreq * visualStepSec
            val normPhase = ((visualPhase / (2.0 * Math.PI)) % 1.0 + 1.0) % 1.0

            val factor: Float = when (mode) {
                WaveformMode.SINE -> (0.5 * (1.0 + sin(visualPhase))).toFloat()
                WaveformMode.SQUARE -> if (normPhase < dutyFraction) 1.0f else 0.0f
                WaveformMode.SAWTOOTH -> normPhase.toFloat()
                WaveformMode.TRIANGLE -> (1.0 - 2.0 * abs(normPhase - 0.5)).toFloat().coerceIn(0f, 1f)
                WaveformMode.CHIRP -> (0.5 * (1.0 + sin(2.0 * Math.PI * instFreq * tSec))).toFloat()
                else -> 1.0f
            }

            val a = (amp * factor).toInt().coerceIn(0, 255)
            graphPoints += VibrationGraphView.VibrationPoint(
                stepIndex = i,
                timeMs = (tSec * 1000.0).toFloat(),
                amplitude = a.toFloat(),
                durationMs = (visualStepSec * 1000.0).toFloat()
            )
        }

        val timings = mutableListOf<Long>()
        val amps = mutableListOf<Int>()
        val hapticStepMs = (totalDuration / 180L).coerceIn(20L, 50L)
        val hapticSteps = (totalDuration / hapticStepMs).toInt().coerceIn(10, 250)
        var hapticPhase = 0.0
        for (i in 0 until hapticSteps) {
            val tSec = i * (hapticStepMs / 1000.0)
            val instFreq = if (mode == WaveformMode.CHIRP) {
                val prog = (i.toFloat() / hapticSteps).coerceIn(0f, 1f)
                startFreq + (endFreq - startFreq) * prog
            } else {
                freq
            }

            hapticPhase += 2.0 * Math.PI * instFreq * (hapticStepMs / 1000.0)
            val normPhase = ((hapticPhase / (2.0 * Math.PI)) % 1.0 + 1.0) % 1.0

            val factor: Float = when (mode) {
                WaveformMode.SINE -> (0.5 * (1.0 + sin(hapticPhase))).toFloat()
                WaveformMode.SQUARE -> if (normPhase < dutyFraction) 1.0f else 0.0f
                WaveformMode.SAWTOOTH -> normPhase.toFloat()
                WaveformMode.TRIANGLE -> (1.0 - 2.0 * abs(normPhase - 0.5)).toFloat().coerceIn(0f, 1f)
                WaveformMode.CHIRP -> (0.5 * (1.0 + sin(2.0 * Math.PI * instFreq * tSec))).toFloat()
                else -> 1.0f
            }

            val a = (amp * factor).toInt().coerceIn(0, 255)
            timings += hapticStepMs
            amps += a
        }

        val isContinuous = mode != WaveformMode.SQUARE
        return GeneratedWaveform(graphPoints, isContinuous, timings.toLongArray(), amps.toIntArray(), totalDuration)
    }

    private fun runWaveformDirect(waveform: GeneratedWaveform, looping: Boolean) {
        stopAll()
        if (waveform.timings.isEmpty()) return
        currentActiveWaveform = waveform

        binding.graphView.setPattern(waveform.graphPoints, waveform.isContinuous)
        val repeat = if (looping) 0 else -1
        Haptics.playWaveform(
            vibrator,
            waveform.timings,
            waveform.amplitudes,
            repeat
        )

        setPlaybackUiState(playing = true, looping = looping)
        activePlaybackJob = lifecycleScope.launch(Dispatchers.Main) {
            var startTime = System.currentTimeMillis()
            while (isActive) {
                val activeWave = currentActiveWaveform ?: break
                val totalTime = activeWave.totalTimeMs.coerceAtLeast(100L)
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > totalTime) {
                    if (isLoopingMode) {
                        startTime = System.currentTimeMillis()
                    } else {
                        break
                    }
                }
                binding.graphView.setPlaybackProgress((elapsed % totalTime).toFloat())
                delay(16)
            }
            setPlaybackUiState(playing = false, looping = false)
        }
    }

    private fun wireTests() {
        presetBindings.clear()
        val oneShots = listOf(
            PresetItem(R.string.vibration_oneshot_1_title, R.string.vibration_oneshot_1_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 255f, 18f),
                    VibrationGraphView.VibrationPoint(1, 90f, 255f, 18f)
                )
                binding.graphView.setPattern(points, false)
                if (hasPrimitives) {
                    Haptics.playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_CLICK, 0L)
                    binding.root.postDelayed({
                        Haptics.playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_CLICK, 0L)
                    }, 90L)
                } else {
                    Haptics.playWaveform(vibrator, longArrayOf(0, 18, 72, 18), intArrayOf(0, 255, 0, 255), -1)
                }
                animateGraphProgress(120L)
            },
            PresetItem(R.string.vibration_oneshot_2_title, R.string.vibration_oneshot_2_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 255f, 12f)
                )
                binding.graphView.setPattern(points, false)
                if (hasPrimitives) {
                    Haptics.playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_CLICK, 0L)
                } else if (hasAmplitude) {
                    Haptics.playOneShot(vibrator, 12L, 255)
                } else {
                    Haptics.playOneShot(vibrator, 20L, 0)
                }
                animateGraphProgress(30L)
            },
            PresetItem(R.string.vibration_oneshot_3_title, R.string.vibration_oneshot_3_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 255f, 45f),
                    VibrationGraphView.VibrationPoint(1, 140f, 170f, 22f)
                )
                binding.graphView.setPattern(points, false)
                if (hasPrimitives) {
                    Haptics.playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_THUD, 0L)
                    binding.root.postDelayed({
                        Haptics.playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_CLICK, 0L)
                    }, 140L)
                } else {
                    Haptics.playWaveform(vibrator, longArrayOf(0, 45, 95, 22), intArrayOf(0, 255, 0, 170), -1)
                }
                animateGraphProgress(180L)
            },
            PresetItem(R.string.vibration_oneshot_4_title, R.string.vibration_oneshot_4_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 255f, 65f)
                )
                binding.graphView.setPattern(points, false)
                if (hasPrimitives) {
                    Haptics.playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_THUD, 0L)
                } else if (hasAmplitude) {
                    Haptics.playOneShot(vibrator, 65L, 255)
                } else {
                    Haptics.playOneShot(vibrator, 65L, 0)
                }
                animateGraphProgress(85L)
            },
            PresetItem(R.string.vibration_oneshot_5_title, R.string.vibration_oneshot_5_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 130f, 8f)
                )
                binding.graphView.setPattern(points, false)
                if (hasPrimitives) {
                    Haptics.playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_TICK, 0L)
                } else if (hasAmplitude) {
                    Haptics.playOneShot(vibrator, 8L, 130)
                } else {
                    Haptics.playOneShot(vibrator, 15L, 0)
                }
                animateGraphProgress(25L)
            },
            PresetItem(R.string.vibration_oneshot_6_title, R.string.vibration_oneshot_6_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 200f, 15f),
                    VibrationGraphView.VibrationPoint(1, 45f, 230f, 15f),
                    VibrationGraphView.VibrationPoint(2, 90f, 255f, 15f)
                )
                binding.graphView.setPattern(points, false)
                Haptics.playWaveform(vibrator, longArrayOf(0, 15, 30, 15, 30, 15), intArrayOf(0, 200, 0, 230, 0, 255), -1)
                animateGraphProgress(130L)
            },
            PresetItem(R.string.vibration_oneshot_7_title, R.string.vibration_oneshot_7_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 160f, 20f),
                    VibrationGraphView.VibrationPoint(1, 60f, 255f, 35f)
                )
                binding.graphView.setPattern(points, false)
                Haptics.playWaveform(vibrator, longArrayOf(0, 20, 40, 35), intArrayOf(0, 160, 0, 255), -1)
                animateGraphProgress(120L)
            },
            PresetItem(R.string.vibration_oneshot_8_title, R.string.vibration_oneshot_8_desc, false) {
                stopAll()
                val points = listOf(
                    VibrationGraphView.VibrationPoint(0, 0f, 180f, 30f),
                    VibrationGraphView.VibrationPoint(1, 70f, 255f, 60f)
                )
                binding.graphView.setPattern(points, false)
                Haptics.playWaveform(vibrator, longArrayOf(0, 30, 40, 60), intArrayOf(0, 180, 0, 255), -1)
                animateGraphProgress(150L)
            }
        )

        val loopingRhythms = listOf(
            PresetItem(R.string.vibration_loop_1_title, R.string.vibration_loop_1_desc, true) {
                val wave = buildDiscreteWaveform(
                    listOf(
                        EditablePoint(90L, 180),
                        EditablePoint(90L, 200),
                        EditablePoint(120L, 230),
                        EditablePoint(220L, 255)
                    )
                )
                runWaveformDirect(wave, looping = true)
            },
            PresetItem(R.string.vibration_loop_2_title, R.string.vibration_loop_2_desc, true) {
                val wave = buildDiscreteWaveform(
                    listOf(
                        EditablePoint(60L, 220),
                        EditablePoint(60L, 190),
                        EditablePoint(60L, 240),
                        EditablePoint(140L, 255)
                    )
                )
                runWaveformDirect(wave, looping = true)
            },
            PresetItem(R.string.vibration_loop_3_title, R.string.vibration_loop_3_desc, true) {
                val wave = buildContinuousWaveform(WaveformMode.SINE, 25f, 220, 3000L)
                runWaveformDirect(wave, looping = true)
            },
            PresetItem(R.string.vibration_loop_4_title, R.string.vibration_loop_4_desc, true) {
                val wave = buildContinuousWaveform(WaveformMode.CHIRP, 10f, 240, 2000L, startFreq = 10f, endFreq = 50f)
                runWaveformDirect(wave, looping = true)
            },
            PresetItem(R.string.vibration_loop_5_title, R.string.vibration_loop_5_desc, true) {
                val wave = buildContinuousWaveform(WaveformMode.SQUARE, 4f, 230, 2000L, dutyFraction = 0.2)
                runWaveformDirect(wave, looping = true)
            },
            PresetItem(R.string.vibration_loop_6_title, R.string.vibration_loop_6_desc, true) {
                val wave = buildDiscreteWaveform(
                    listOf(
                        EditablePoint(60L, 220), EditablePoint(60L, 220), EditablePoint(60L, 220),
                        EditablePoint(160L, 255), EditablePoint(160L, 255), EditablePoint(160L, 255),
                        EditablePoint(60L, 220), EditablePoint(60L, 220), EditablePoint(60L, 220)
                    )
                )
                runWaveformDirect(wave, looping = true)
            },
            PresetItem(R.string.vibration_loop_7_title, R.string.vibration_loop_7_desc, true) {
                val wave = buildContinuousWaveform(WaveformMode.TRIANGLE, 1f, 200, 4000L)
                runWaveformDirect(wave, looping = true)
            }
        )

        populatePresetGroup(binding.containerOneshotPresets, oneShots)
        populatePresetGroup(binding.containerLoopingPresets, loopingRhythms)
    }

    private fun wireVoiceResponsive() {
        binding.sliderVoiceSensitivity.addOnChangeListener { _, value, _ ->
            voiceSensitivity = value.toInt()
            binding.labelVoiceSensitivity.text =
                getString(R.string.vibration_voice_sensitivity, voiceSensitivity)
        }
        binding.labelVoiceSensitivity.text =
            getString(R.string.vibration_voice_sensitivity, binding.sliderVoiceSensitivity.value.toInt())
        binding.labelVoiceInputLevel.text =
            getString(R.string.vibration_voice_input_level, 0)

        binding.btnVoiceListen.setOnClickListener {
            if (isVoiceListening) {
                stopVoiceListening()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startVoiceListening()
                } else {
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startVoiceListening() {
        activePlaybackJob?.cancel()
        activePlaybackJob = null
        currentActiveWaveform = null
        pendingHapticDispatch?.let { hapticHandler.removeCallbacks(it) }
        pendingHapticDispatch = null
        Haptics.cancel(vibrator)
        activePresetTitleRes = null
        setPlaybackUiState(playing = false, looping = false)

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize.coerceAtLeast(2048)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Toast.makeText(this, R.string.vibration_voice_permission_denied, Toast.LENGTH_SHORT).show()
                return
            }
            record.startRecording()
            audioRecord = record
            isVoiceListening = true

            val colorError = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError, Color.parseColor("#BA1A1A"))
            val colorOnError = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnError, Color.WHITE)
            binding.btnVoiceListen.setText(R.string.vibration_voice_stop)
            binding.btnVoiceListen.backgroundTintList = ColorStateList.valueOf(colorError)
            binding.btnVoiceListen.setTextColor(colorOnError)

            voiceListeningJob = lifecycleScope.launch(Dispatchers.Default) {
                val audioBuffer = ShortArray(1024)
                var lastVibeTime = 0L

                while (isActive && isVoiceListening) {
                    val read = record.read(audioBuffer, 0, audioBuffer.size)
                    if (read > 0) {
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val sample = audioBuffer[i].toDouble()
                            sumSq += sample * sample
                        }
                        val rms = sqrt(sumSq / read)
                        val sensitivityFactor = voiceSensitivity * 0.5
                        val rawNormalized = (rms / 6000.0) * sensitivityFactor
                        val level = (rawNormalized * 100).toInt().coerceIn(0, 100)

                        val now = System.currentTimeMillis()
                        val targetAmp = if (hasAmplitude) {
                            ((level / 100f) * 255).toInt().coerceIn(40, 255)
                        } else {
                            255
                        }
                        val targetDurationMs = (15L + (level / 100f * 185f).toLong()).coerceIn(15L, 200L)
                        val minInterval = (targetDurationMs + 20L).coerceAtLeast(40L)

                        if (level > 8 && now - lastVibeTime >= minInterval) {
                            lastVibeTime = now
                            Haptics.playOneShot(vibrator, targetDurationMs, targetAmp)
                        }

                        withContext(Dispatchers.Main) {
                            if (isVoiceListening) {
                                binding.progressVoiceLevel.progress = level
                                binding.labelVoiceInputLevel.text =
                                    getString(R.string.vibration_voice_input_level, level)
                                if (level > 8) {
                                    val point = VibrationGraphView.VibrationPoint(0, 0f, targetAmp.toFloat(), targetDurationMs.toFloat())
                                    binding.graphView.setPattern(listOf(point), false)
                                    binding.graphView.setPlaybackProgress(targetDurationMs / 2f)
                                } else {
                                    binding.graphView.setPlaybackProgress(-1f)
                                }
                            }
                        }
                    }
                    delay(20)
                }
            }
        } catch (e: SecurityException) {
            stopVoiceListening()
            Toast.makeText(this, R.string.vibration_voice_permission_denied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            stopVoiceListening()
        }
    }

    private fun stopVoiceListening() {
        isVoiceListening = false
        voiceListeningJob?.cancel()
        voiceListeningJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        val colorPrimary = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#00BCD4"))
        val colorOnPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        binding.btnVoiceListen.setText(R.string.vibration_voice_start)
        binding.btnVoiceListen.backgroundTintList = ColorStateList.valueOf(colorPrimary)
        binding.btnVoiceListen.setTextColor(colorOnPrimary)
        binding.progressVoiceLevel.progress = 0
        binding.labelVoiceInputLevel.text = getString(R.string.vibration_voice_input_level, 0)
        binding.graphView.setPlaybackProgress(-1f)
    }

    private fun animateGraphProgress(durationMs: Long) {
        activePlaybackJob?.cancel()
        setPlaybackUiState(playing = true, looping = false)
        activePlaybackJob = lifecycleScope.launch(Dispatchers.Main) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > durationMs + 40) break
                binding.graphView.setPlaybackProgress(elapsed.toFloat())
                delay(16)
            }
            setPlaybackUiState(playing = false, looping = false)
        }
    }

    private fun stopAll() {
        if (isVoiceListening) {
            stopVoiceListening()
        }
        activePlaybackJob?.cancel()
        activePlaybackJob = null
        currentActiveWaveform = null
        pendingHapticDispatch?.let { hapticHandler.removeCallbacks(it) }
        pendingHapticDispatch = null
        Haptics.cancel(vibrator)
        setPlaybackUiState(playing = false, looping = false)
    }

    override fun onStop() {
        super.onStop()
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceListening()
    }
}
