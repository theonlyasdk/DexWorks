package com.asdk.tools.dexworks

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private var baseScale = 1f
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f
    private val maxUserScale = 6f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                userScale = (userScale * factor).coerceIn(1f, maxUserScale)
                applyTransform()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                userScale = if (userScale > 1.05f) 1f else 2.5f
                if (userScale == 1f) {
                    panX = 0f
                    panY = 0f
                }
                applyTransform()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (userScale <= 1.01f) return false
                panX -= distanceX
                panY -= distanceY
                applyTransform()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetTransform() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetTransform()
    }

    private fun resetTransform() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        if (dw <= 0 || dh <= 0 || width == 0 || height == 0) return
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        baseScale = min(vw / dw, vh / dh)
        userScale = 1f
        panX = 0f
        panY = 0f
        applyTransform()
    }

    private fun applyTransform() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        if (dw <= 0 || dh <= 0 || vw <= 0f || vh <= 0f) return

        val scale = baseScale * userScale
        val drawnW = dw * scale
        val drawnH = dh * scale

        val maxPanX = max(0f, (drawnW - vw) / 2f)
        val maxPanY = max(0f, (drawnH - vh) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)

        val tx = (vw - drawnW) / 2f + panX
        val ty = (vh - drawnH) / 2f + panY

        drawMatrix.reset()
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(tx, ty)
        imageMatrix = drawMatrix
    }

    val isZoomed: Boolean
        get() = userScale > 1.01f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(isZoomed)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
