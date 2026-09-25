package com.asdk.tools.dexworks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible

class FastScrollBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onScrubTo: ((Int) -> Unit)? = null
    var onScrubFinished: (() -> Unit)? = null

    private var firstVisible: Int = 0
    private var lastVisible: Int = 0
    private var totalItems: Int = 0
    private var isDragging: Boolean = false
    private var lastHapticPosition: Int = -1

    private val density = resources.displayMetrics.density
    private val trackWidth = 4f * density
    private val thumbWidth = 10f * density
    private val thumbActiveWidth = 14f * density
    private val minThumbHeight = 48f * density
    private val corner = 8f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
        alpha = 220
    }

    private val hideRunnable = Runnable {
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { isVisible = false }
            .start()
    }

    init {
        isFocusable = true
        alpha = 0f
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    fun updateRange(first: Int, last: Int, total: Int) {
        firstVisible = first.coerceAtLeast(0)
        lastVisible = last.coerceAtLeast(0)
        totalItems = total.coerceAtLeast(0)
        invalidate()
        showBriefly()
    }

    fun showBriefly() {
        if (totalItems <= 0) return
        animate().cancel()
        removeCallbacks(hideRunnable)
        if (!isVisible) {
            isVisible = true
            alpha = 0f
        }
        animate().alpha(1f).setDuration(150).start()
        postDelayed(hideRunnable, 1500)
    }

    fun hideNow() {
        removeCallbacks(hideRunnable)
        animate().cancel()
        isVisible = false
        alpha = 0f
    }

    private fun trackCenterX(): Float = width - 10f * density

    private fun thumbRect(): RectF {
        val centerX = trackCenterX()
        val w = if (isDragging) thumbActiveWidth else thumbWidth
        if (totalItems <= 0 || height <= 0) {
            return RectF(centerX - w / 2f, 0f, centerX + w / 2f, minThumbHeight)
        }
        val top = (firstVisible.toFloat() / totalItems) * height
        var bottom = ((lastVisible + 1).toFloat() / totalItems) * height
        if (bottom - top < minThumbHeight) {
            bottom = (top + minThumbHeight).coerceAtMost(height.toFloat())
        }
        return RectF(centerX - w / 2f, top, centerX + w / 2f, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0 || totalItems <= 0) return
        val centerX = trackCenterX()
        canvas.drawRoundRect(
            centerX - trackWidth / 2f, 0f,
            centerX + trackWidth / 2f, height.toFloat(),
            corner, corner, trackPaint
        )
        val thumb = thumbRect()
        canvas.drawRoundRect(thumb, corner, corner, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (totalItems <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                animate().cancel()
                removeCallbacks(hideRunnable)
                alpha = 1f
                lastHapticPosition = -1
                scrubTo(event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                scrubTo(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                lastHapticPosition = -1
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                onScrubFinished?.invoke()
                showBriefly()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrubTo(y: Float) {
        val fraction = (y / height).coerceIn(0f, 1f)
        val position = (fraction * totalItems).toInt().coerceIn(0, totalItems - 1)
        if (position != lastHapticPosition) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticPosition = position
        }
        invalidate()
        onScrubTo?.invoke(position)
    }
}
