package com.asdk.tools.dexworks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

class CylinderChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(
        val id: String,
        val label: String,
        val percentage: Float,
        @param:ColorInt val color: Int
    )

    private var slices: List<Slice> = emptyList()
    private var animationProgress: Float = 1f
    private var animator: ValueAnimator? = null

    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.argb(40, 0, 0, 0)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tempRect = RectF()
    private val slicePath = Path()

    fun setData(newSlices: List<Slice>, animate: Boolean = true) {
        this.slices = newSlices
        animator?.cancel()

        if (animate) {
            animationProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 850
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    animationProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animationProgress = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (slices.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0) return

        val density = resources.displayMetrics.density
        val paddingHorizontal = 40f * density
        val paddingTop = 16f * density
        val paddingBottom = 24f * density

        val cylinderLeft = paddingHorizontal
        val cylinderRight = viewWidth - paddingHorizontal
        val cylinderWidth = cylinderRight - cylinderLeft

        if (cylinderWidth <= 0) return

        val ellipseHeight = cylinderWidth * 0.32f
        val ellipseRadiusY = ellipseHeight / 2f

        val totalAvailableHeight = viewHeight - paddingTop - paddingBottom - ellipseHeight
        if (totalAvailableHeight <= 0) return

        val animatedHeight = totalAvailableHeight * animationProgress

        // Ground shadow under the cylinder
        val groundY = viewHeight - paddingBottom - ellipseRadiusY
        tempRect.set(
            cylinderLeft + cylinderWidth * 0.08f,
            groundY - ellipseRadiusY * 0.6f + 6f * density,
            cylinderRight - cylinderWidth * 0.08f,
            groundY + ellipseRadiusY * 1.2f + 6f * density
        )
        shadowPaint.color = Color.argb(35, 0, 0, 0)
        canvas.drawOval(tempRect, shadowPaint)

        // Base bottom Y (center of bottom ellipse)
        val cylinderBaseY = viewHeight - paddingBottom - ellipseRadiusY

        // Draw slices from bottom to top
        var currentBottomY = cylinderBaseY

        for (i in slices.indices.reversed()) {
            val slice = slices[i]
            val sliceHeight = (slice.percentage / 100f) * animatedHeight
            if (sliceHeight <= 0.5f) continue

            val currentTopY = currentBottomY - sliceHeight

            drawCylinderSlice(
                canvas = canvas,
                left = cylinderLeft,
                right = cylinderRight,
                topY = currentTopY,
                bottomY = currentBottomY,
                ellipseRadiusY = ellipseRadiusY,
                baseColor = slice.color,
                isTopMost = (i == 0)
            )

            currentBottomY = currentTopY
        }
    }

    private fun drawCylinderSlice(
        canvas: Canvas,
        left: Float,
        right: Float,
        topY: Float,
        bottomY: Float,
        ellipseRadiusY: Float,
        @ColorInt baseColor: Int,
        isTopMost: Boolean
    ) {
        val width = right - left

        // 3D Shading colors for side wall
        val darkEdge = ColorUtils.blendARGB(baseColor, Color.BLACK, 0.45f)
        val highlight = ColorUtils.blendARGB(baseColor, Color.WHITE, 0.28f)
        val midColor = baseColor
        val shadow = ColorUtils.blendARGB(baseColor, Color.BLACK, 0.30f)

        val sideGradient = LinearGradient(
            left, topY, right, topY,
            intArrayOf(darkEdge, highlight, midColor, shadow, darkEdge),
            floatArrayOf(0.0f, 0.25f, 0.60f, 0.85f, 1.0f),
            Shader.TileMode.CLAMP
        )
        sidePaint.shader = sideGradient

        // Build side wall path connecting top ellipse to bottom ellipse
        slicePath.reset()
        // Top left
        slicePath.moveTo(left, topY)
        // Top front arc (left to right)
        tempRect.set(left, topY - ellipseRadiusY, right, topY + ellipseRadiusY)
        slicePath.arcTo(tempRect, 180f, -180f, false)
        // Right side down
        slicePath.lineTo(right, bottomY)
        // Bottom front arc (right to left)
        tempRect.set(left, bottomY - ellipseRadiusY, right, bottomY + ellipseRadiusY)
        slicePath.arcTo(tempRect, 0f, 180f, false)
        // Left side up
        slicePath.close()

        canvas.drawPath(slicePath, sidePaint)

        // Draw top cap ellipse
        val capHighlight = ColorUtils.blendARGB(baseColor, Color.WHITE, 0.20f)
        val capShadow = ColorUtils.blendARGB(baseColor, Color.BLACK, 0.15f)
        val capGradient = LinearGradient(
            left, topY - ellipseRadiusY, right, topY + ellipseRadiusY,
            intArrayOf(capHighlight, baseColor, capShadow),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
        capPaint.shader = capGradient

        tempRect.set(left, topY - ellipseRadiusY, right, topY + ellipseRadiusY)
        canvas.drawOval(tempRect, capPaint)
        canvas.drawOval(tempRect, strokePaint)
    }
}
