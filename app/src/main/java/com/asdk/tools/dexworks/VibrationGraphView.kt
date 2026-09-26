package com.asdk.tools.dexworks

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

class VibrationGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class VibrationPoint(
        val stepIndex: Int,
        val timeMs: Float,
        val amplitude: Float,
        val durationMs: Float,
        val label: String = ""
    )

    private val points = mutableListOf<VibrationPoint>()
    private var isContinuousCurve: Boolean = false
    private var playbackProgressTimeMs: Float = -1f

    // Zoom and Pan on Time X-Axis
    private var zoomX: Float = 1.0f  // 1.0f (fit all) up to 25.0f
    private var scrollOffsetRatio: Float = 0f  // 0f .. (1f - 1f/zoomX)

    private var lastTouchX: Float = 0f
    private var isDragging: Boolean = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = zoomX
                val newZoom = (zoomX * detector.scaleFactor).coerceIn(1.0f, 25.0f)
                if (oldZoom != newZoom) {
                    val focusX = detector.focusX
                    val padLeft = 68f
                    val padRight = 32f
                    val plotW = (width.toFloat() - padLeft - padRight).coerceAtLeast(1f)
                    val focusRatio = ((focusX - padLeft) / plotW).coerceIn(0f, 1f)

                    // Keep point under fingers stable during zoom
                    val currentCenter = scrollOffsetRatio + focusRatio / oldZoom
                    zoomX = newZoom
                    val maxOffset = (1f - 1f / zoomX).coerceAtLeast(0f)
                    scrollOffsetRatio = (currentCenter - focusRatio / zoomX).coerceIn(0f, maxOffset)
                    invalidate()
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomX = 1.0f
                scrollOffsetRatio = 0f
                invalidate()
                return true
            }
        }
    )

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val waveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val waveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val cursorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePath = Path()
    private val fillPath = Path()
    private var cachedMaxTime: Float = 2000f

    fun setPattern(newPoints: List<VibrationPoint>, continuous: Boolean = false) {
        points.clear()
        points.addAll(newPoints)
        isContinuousCurve = continuous
        cachedMaxTime = (points.maxOfOrNull { it.timeMs + it.durationMs } ?: 2000f).coerceAtLeast(300f)
        invalidate()
    }

    fun setPlaybackProgress(timeMs: Float) {
        playbackProgressTimeMs = timeMs
        if (timeMs >= 0f && zoomX > 1.0f) {
            val progressRatio = (timeMs / cachedMaxTime).coerceIn(0f, 1f)
            val visibleWindow = 1f / zoomX
            val maxOffset = (1f - visibleWindow).coerceAtLeast(0f)

            // Auto-scroll so cursor stays centered (at ~40% of viewport)
            val targetOffset = (progressRatio - visibleWindow * 0.4f).coerceIn(0f, maxOffset)
            scrollOffsetRatio = targetOffset
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && zoomX > 1.0f) {
                    val dx = event.x - lastTouchX
                    val padLeft = 68f
                    val padRight = 32f
                    val plotW = (width.toFloat() - padLeft - padRight).coerceAtLeast(1f)
                    val deltaRatio = dx / (zoomX * plotW)
                    val maxOffset = (1f - 1f / zoomX).coerceAtLeast(0f)
                    scrollOffsetRatio = (scrollOffsetRatio - deltaRatio).coerceIn(0f, maxOffset)
                    lastTouchX = event.x
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Resolve Material 3 colors dynamically from theme
        val colorSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.parseColor("#12141A"))
        val colorPrimary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#00BCD4"))
        val colorOutline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#44888888"))
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#B0BEC5"))
        val colorTertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary, Color.parseColor("#FF9800"))

        axisPaint.color = colorOutline
        gridPaint.color = ColorUtils.setAlphaComponent(colorOutline, 60)
        waveStrokePaint.color = colorPrimary
        labelPaint.color = colorOnSurfaceVariant
        cursorPaint.color = colorTertiary
        cursorLinePaint.color = colorTertiary
        cursorGlowPaint.color = ColorUtils.setAlphaComponent(colorTertiary, 90)

        // Background
        canvas.drawColor(colorSurface)

        // Margins & plot bounds
        val padLeft = 68f
        val padRight = 32f
        val padTop = 32f
        val padBottom = 48f

        val plotLeft = padLeft
        val plotRight = w - padRight
        val plotTop = padTop
        val plotBottom = h - padBottom
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        if (plotW <= 0 || plotH <= 0) return

        val maxTime = cachedMaxTime
        val maxAmp = 255f

        fun timeToX(t: Float): Float {
            val u = t / maxTime
            return plotLeft + (u - scrollOffsetRatio) * zoomX * plotW
        }
        fun ampToY(a: Float): Float = plotBottom - (a / maxAmp).coerceIn(0f, 1f) * plotH

        // 1. Draw Horizontal Grid Lines & Y Ticks (255, 128, 0)
        val yTicks = listOf(255f, 128f, 0f)
        for (yAmp in yTicks) {
            val y = ampToY(yAmp)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            val label = yAmp.toInt().toString()
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, plotLeft - 10f, y + 8f, labelPaint)
        }

        // Visible time range in current viewport
        val visibleStartMs = scrollOffsetRatio * maxTime
        val visibleEndMs = (scrollOffsetRatio + 1f / zoomX) * maxTime
        val visibleSpanMs = visibleEndMs - visibleStartMs

        // 2. Draw Dynamic Time Ticks on X-axis
        val tickIntervalMs = calculateOptimalTickInterval(visibleSpanMs)
        val firstTick = (Math.floor((visibleStartMs / tickIntervalMs).toDouble()) * tickIntervalMs).toFloat()

        labelPaint.textAlign = Paint.Align.CENTER
        var tickTime = firstTick
        while (tickTime <= visibleEndMs + tickIntervalMs * 0.5f) {
            if (tickTime >= 0f && tickTime <= maxTime + 1f) {
                val x = timeToX(tickTime)
                if (x in (plotLeft - 1f)..(plotRight + 1f)) {
                    canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
                    val label = formatTickLabel(tickTime, visibleSpanMs)
                    canvas.drawText(label, x, plotBottom + 32f, labelPaint)
                }
            }
            tickTime += tickIntervalMs
        }

        // Clip waveform and playhead to the inner plot viewport
        canvas.save()
        canvas.clipRect(plotLeft, plotTop - 20f, plotRight, plotBottom + 2f)

        // 3. Build and Draw Waveform Path (reusing strokePath & fillPath)
        if (points.isNotEmpty()) {
            strokePath.rewind()
            fillPath.rewind()

            val zeroY = ampToY(0f)

            if (!isContinuousCurve) {
                // Discrete step pulses with viewport culling
                var currentX = timeToX(0f)
                strokePath.moveTo(currentX, zeroY)
                fillPath.moveTo(plotLeft, zeroY)

                for (pt in points) {
                    val ptEndMs = pt.timeMs + pt.durationMs
                    if (ptEndMs < visibleStartMs) {
                        currentX = timeToX(ptEndMs)
                        continue
                    }
                    if (pt.timeMs > visibleEndMs) break

                    val startX = timeToX(pt.timeMs)
                    val endX = timeToX(ptEndMs)
                    val pulseY = ampToY(pt.amplitude)

                    if (startX > currentX) {
                        strokePath.lineTo(startX, zeroY)
                        fillPath.lineTo(startX, zeroY)
                    }

                    strokePath.lineTo(startX, pulseY)
                    fillPath.lineTo(startX, pulseY)

                    strokePath.lineTo(endX, pulseY)
                    fillPath.lineTo(endX, pulseY)

                    strokePath.lineTo(endX, zeroY)
                    fillPath.lineTo(endX, zeroY)

                    currentX = endX

                    if (pt.amplitude > 0 && (endX - startX) > 20f && endX >= plotLeft && startX <= plotRight) {
                        val midX = (startX + endX) / 2f
                        labelPaint.textAlign = Paint.Align.CENTER
                        labelPaint.color = colorOnSurface
                        val badge = "P${pt.stepIndex + 1}"
                        canvas.drawText(badge, midX, pulseY - 8f, labelPaint)
                    }
                }

                val finalX = timeToX(maxTime)
                if (finalX > currentX) {
                    strokePath.lineTo(finalX, zeroY)
                    fillPath.lineTo(finalX, zeroY)
                }
                fillPath.close()
            } else {
                // Continuous curve with binary search viewport culling
                val startIndex = points.binarySearch { it.timeMs.compareTo(visibleStartMs) }.let {
                    if (it >= 0) (it - 2).coerceAtLeast(0)
                    else (-it - 3).coerceAtLeast(0)
                }
                val endIndex = points.binarySearch { it.timeMs.compareTo(visibleEndMs) }.let {
                    if (it >= 0) (it + 2).coerceAtMost(points.size - 1)
                    else (-it).coerceAtMost(points.size - 1)
                }

                var hasFirst = false
                val startX = timeToX(points[startIndex].timeMs)
                fillPath.moveTo(startX, zeroY)

                for (i in startIndex..endIndex) {
                    val pt = points[i]
                    val x = timeToX(pt.timeMs)
                    val y = ampToY(pt.amplitude)
                    if (!hasFirst) {
                        strokePath.moveTo(x, y)
                        fillPath.lineTo(x, y)
                        hasFirst = true
                    } else {
                        strokePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                val endX = timeToX(points[endIndex].timeMs)
                fillPath.lineTo(endX, zeroY)
                fillPath.close()
            }

            // Fill area below wave with smooth gradient
            waveFillPaint.shader = LinearGradient(
                0f, plotTop, 0f, plotBottom,
                ColorUtils.setAlphaComponent(colorPrimary, 110),
                ColorUtils.setAlphaComponent(colorPrimary, 10),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, waveFillPaint)
            waveFillPaint.shader = null

            // Draw crisp waveform outline
            canvas.drawPath(strokePath, waveStrokePaint)
        }

        // 4. Animated Playhead (Live Sweep with Auto-Scroll)
        if (playbackProgressTimeMs >= 0f) {
            val curTime = playbackProgressTimeMs.coerceAtMost(maxTime)
            val curX = timeToX(curTime)

            // Interpolate current amplitude at curTime using binary search
            var curAmp = 0f
            if (!isContinuousCurve) {
                for (pt in points) {
                    if (curTime < pt.timeMs) break
                    if (curTime <= pt.timeMs + pt.durationMs) {
                        curAmp = pt.amplitude
                        break
                    }
                }
            } else {
                val idx = points.binarySearch { it.timeMs.compareTo(curTime) }
                val nearestIdx = if (idx >= 0) idx else (-idx - 1).coerceIn(0, points.size - 1)
                curAmp = points[nearestIdx].amplitude
            }

            val curY = ampToY(curAmp)

            // Vertical cursor line
            canvas.drawLine(curX, plotTop, curX, plotBottom, cursorLinePaint)

            // Cursor bead & glowing halo
            canvas.drawCircle(curX, curY, 13f, cursorGlowPaint)
            canvas.drawCircle(curX, curY, 6.5f, cursorPaint)

            // Time badge at top of playhead
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.color = colorTertiary
            val timeBadge = if (maxTime >= 5000f) {
                String.format("%.2fs", curTime / 1000f)
            } else {
                "${curTime.toInt()}ms"
            }
            canvas.drawText(timeBadge, curX, plotTop - 8f, labelPaint)
        }

        canvas.restore()

        // Draw Axes Border
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)

        // Zoom hint overlay in corner if zoomed
        if (zoomX > 1.05f) {
            labelPaint.textAlign = Paint.Align.RIGHT
            labelPaint.color = colorOnSurfaceVariant
            canvas.drawText("${String.format("%.1f", zoomX)}x", plotRight, plotTop + 24f, labelPaint)
        }
    }

    private fun calculateOptimalTickInterval(visibleSpanMs: Float): Float {
        val approxTicks = 5
        val raw = visibleSpanMs / approxTicks
        return when {
            raw >= 10000f -> 10000f
            raw >= 5000f -> 5000f
            raw >= 2000f -> 2000f
            raw >= 1000f -> 1000f
            raw >= 500f -> 500f
            raw >= 250f -> 250f
            raw >= 100f -> 100f
            raw >= 50f -> 50f
            raw >= 20f -> 20f
            else -> 10f
        }
    }

    private fun formatTickLabel(timeMs: Float, visibleSpanMs: Float): String {
        return if (timeMs >= 1000f && visibleSpanMs >= 2000f) {
            val sec = timeMs / 1000f
            if (sec % 1.0f == 0f) "${sec.toInt()}s" else String.format("%.1fs", sec)
        } else {
            "${timeMs.toInt()}ms"
        }
    }
}
