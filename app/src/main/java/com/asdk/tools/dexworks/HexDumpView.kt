package com.asdk.tools.dexworks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.max

class HexDumpView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onByteSelected(offset: Long, value: Int, printable: Boolean)
    }

    companion object {
        const val BYTES_PER_ROW = 16
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
        private const val MIN_SCALE = 0.15f
        private const val MAX_SCALE = 4.0f
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )

    private val hexTable = Array(256) { String.format("%02X", it) }
    private val asciiTable = Array(256) { if (it in 32..126) it.toChar().toString() else "." }

    private val offsetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(12f)
        color = resolveColor(com.google.android.material.R.attr.colorOutline, 0xFF888888.toInt())
    }
    private val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(12f)
        color = resolveColor(androidx.appcompat.R.attr.colorPrimary, 0xFF6200EE.toInt())
    }
    private val hexDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(12f)
        color = resolveColor(com.google.android.material.R.attr.colorOutline, 0xFF888888.toInt())
    }
    private val asciiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(12f)
        color = resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
    }
    private val asciiDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(12f)
        color = resolveColor(com.google.android.material.R.attr.colorOutline, 0xFF888888.toInt())
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(12f)
        color = resolveColor(com.google.android.material.R.attr.colorOutline, 0xFF888888.toInt())
    }
    private val stripePaint = Paint().apply {
        color = resolveColor(com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFFF5F5F5.toInt())
    }
    private val selectionPaint = Paint().apply {
        color = resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFD0BCFF.toInt())
    }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveColor(com.google.android.material.R.attr.colorOutline, 0xFF888888.toInt())
        alpha = 90
    }
    private val surfaceColor = resolveColor(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())

    private var data: ByteArray = ByteArray(0)
    private var baseOffset: Long = 0L
    private var totalSize: Long = 0L
    private var selectedOffset: Long = -1L
    private var bottomInset: Int = 0

    private val charWidth = offsetPaint.measureText("0")
    private val rowHeight = (sp(12f) * 1.6f).coerceAtLeast(dp(20f))
    private val padding = dp(8f)
    private val offsetColW = charWidth * 8f + dp(10f)
    private val byteCellW = charWidth * 2f + dp(6f)
    private val groupGap = dp(8f)
    private val hexBlockW = BYTES_PER_ROW * byteCellW + groupGap
    private val separatorW = dp(12f)
    private val asciiCharW = charWidth + dp(1f)
    private val asciiColW = BYTES_PER_ROW * asciiCharW
    private val contentW = padding + offsetColW + hexBlockW + separatorW + asciiColW + padding
    private val contentH get() = rowsCount * rowHeight + bottomInset
    private val rowsCount: Int
        get() = ceil(data.size.toDouble() / BYTES_PER_ROW).toInt()

    private val scroller = OverScroller(context)
    private var scaleFactor = 1.0f
    private var scrollX = 0f
    private var scrollY = 0f

    private val maxScrollX: Float
        get() = max(0f, contentW * scaleFactor - width)
    private val maxScrollY: Float
        get() = max(0f, contentH * scaleFactor - height)

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val focusX = detector.focusX
                val focusY = detector.focusY
                val factor = scaleFactor / oldScale
                scrollX = ((scrollX + focusX) * factor - focusX).coerceIn(0f, maxScrollX)
                scrollY = ((scrollY + focusY) * factor - focusY).coerceIn(0f, maxScrollY)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                scroller.forceFinished(true)
                scrollByDelta(distanceX, distanceY)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                scroller.forceFinished(true)
                scroller.fling(
                    scrollX.toInt(),
                    scrollY.toInt(),
                    -velocityX.toInt(),
                    -velocityY.toInt(),
                    0,
                    maxScrollX.toInt(),
                    0,
                    maxScrollY.toInt()
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }
        }
    )

    fun setData(bytes: ByteArray, baseOffset: Long, totalSize: Long) {
        this.data = bytes
        this.baseOffset = baseOffset
        this.totalSize = totalSize
        this.selectedOffset = -1L
        scrollX = 0f
        scrollY = 0f
        scaleFactor = 1.0f
        recomputeScrollRange()
        invalidate()
    }

    fun setBottomInset(inset: Int) {
        this.bottomInset = inset
        recomputeScrollRange()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeScrollRange()
    }

    private fun recomputeScrollRange() {
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }

    private fun scrollByDelta(dx: Float, dy: Float) {
        scrollX = (scrollX + dx).coerceIn(0f, maxScrollX)
        scrollY = (scrollY + dy).coerceIn(0f, maxScrollY)
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScrollX)
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScrollY)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleGestureDetector.onTouchEvent(event)
        val isScaling = scaleGestureDetector.isInProgress
        val gestureHandled = if (!isScaling) gestureDetector.onTouchEvent(event) else false
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return isScaling || gestureHandled || super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        if (data.isEmpty()) return
        val contentY = (y + scrollY) / scaleFactor
        val contentX = (x + scrollX) / scaleFactor
        val row = (contentY / rowHeight).toInt().coerceIn(0, rowsCount - 1)
        val byteIndex = hitTestByte(contentX)
        if (byteIndex < 0) return
        val dataIndex = row * BYTES_PER_ROW + byteIndex
        if (dataIndex < 0 || dataIndex >= data.size) return
        val value = data[dataIndex].toInt() and 0xFF
        selectedOffset = baseOffset + dataIndex
        invalidate()
        listener?.onByteSelected(selectedOffset, value, value in 32..126)
    }

    fun getSelectedOffset(): Long = selectedOffset

    fun selectOffset(offset: Long, notify: Boolean = true): Boolean {
        val dataIndex = (offset - baseOffset).toInt()
        if (dataIndex < 0 || dataIndex >= data.size) return false
        val value = data[dataIndex].toInt() and 0xFF
        selectedOffset = offset
        ensureOffsetVisible(offset)
        invalidate()
        if (notify) {
            listener?.onByteSelected(selectedOffset, value, value in 32..126)
        }
        return true
    }

    /**
     * Scrolls so the given byte sits in the middle of the visible area, both
     * vertically and horizontally.
     *
     * [bottomInset] is subtracted from the usable height, so a byte is centred in
     * what the user can actually see rather than behind an expanded bottom sheet.
     */
    fun centerOffsetInView(offset: Long): Boolean {
        val dataIndex = (offset - baseOffset).toInt()
        if (dataIndex < 0 || dataIndex >= data.size) return false
        val row = dataIndex / BYTES_PER_ROW
        val column = dataIndex % BYTES_PER_ROW

        val visibleHeight = (height - bottomInset).coerceAtLeast(rowHeight.toInt())
        val rowCenter = (padding + row * rowHeight + rowHeight / 2f) * scaleFactor
        scrollY = (rowCenter - visibleHeight / 2f).coerceIn(0f, maxScrollY)

        val hexStart = padding + offsetColW
        val cellStart = hexStart + column * byteCellW + if (column >= 8) groupGap else 0f
        val cellCenter = (cellStart + byteCellW / 2f) * scaleFactor
        scrollX = (cellCenter - width / 2f).coerceIn(0f, maxScrollX)

        invalidate()
        return true
    }

    private fun ensureOffsetVisible(offset: Long) {
        val dataIndex = (offset - baseOffset).toInt()
        if (dataIndex < 0 || dataIndex >= data.size) return
        val row = dataIndex / BYTES_PER_ROW
        val targetY = (padding + row * rowHeight) * scaleFactor
        val visibleHeight = (height - bottomInset).toFloat().coerceAtLeast(rowHeight)
        if (targetY < scrollY) {
            scrollY = targetY.coerceIn(0f, maxScrollY)
        } else if (targetY + rowHeight * scaleFactor > scrollY + visibleHeight) {
            scrollY = (targetY + rowHeight * scaleFactor - visibleHeight).coerceIn(0f, maxScrollY)
        }
    }

    private fun hitTestByte(contentX: Float): Int {
        val hexStart = padding + offsetColW
        if (contentX in hexStart..(hexStart + hexBlockW)) {
            val relative = contentX - hexStart
            for (i in 0 until BYTES_PER_ROW) {
                val cellStart = i * byteCellW + if (i >= 8) groupGap else 0f
                if (relative in cellStart..(cellStart + byteCellW)) return i
            }
        }
        val asciiStart = hexStart + hexBlockW + separatorW
        if (contentX in asciiStart..(asciiStart + asciiColW)) {
            val relative = contentX - asciiStart
            val i = (relative / asciiCharW).toInt()
            if (i in 0 until BYTES_PER_ROW) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        canvas.drawColor(surfaceColor)
        canvas.save()
        canvas.translate(-scrollX, -scrollY)
        canvas.scale(scaleFactor, scaleFactor)

        val scaledRowHeight = rowHeight * scaleFactor
        val firstRow = ((scrollY / scaledRowHeight).toInt() - 1).coerceAtLeast(0)
        val lastRow = (((scrollY + height) / scaledRowHeight).toInt() + 1).coerceAtMost(rowsCount - 1)

        val hexStart = padding + offsetColW
        val asciiStart = hexStart + hexBlockW + separatorW
        val baseline = baselineInRow()

        for (row in firstRow..lastRow) {
            val rowTop = row * rowHeight
            val dataStart = row * BYTES_PER_ROW
            val count = minOf(BYTES_PER_ROW, data.size - dataStart)
            if (count <= 0) continue

            if (row % 2 == 0) {
                canvas.drawRect(
                    0f,
                    rowTop,
                    contentW,
                    rowTop + rowHeight,
                    stripePaint
                )
            }

            canvas.drawText(hexOffset(baseOffset + dataStart), padding, rowTop + baseline, offsetPaint)
            canvas.drawText("|", hexStart + hexBlockW + separatorW / 3f, rowTop + baseline, separatorPaint)

            for (i in 0 until count) {
                val value = data[dataStart + i].toInt() and 0xFF
                val printable = value in 32..126
                val cellX = hexStart + i * byteCellW + if (i >= 8) groupGap else 0f
                val asciiCellX = asciiStart + i * asciiCharW

                if (baseOffset + dataStart + i == selectedOffset) {
                    canvas.drawRect(
                        cellX - dp(2f),
                        rowTop + dp(2f),
                        cellX + charWidth * 2f + dp(2f),
                        rowTop + rowHeight - dp(2f),
                        selectionPaint
                    )
                    canvas.drawRect(
                        asciiCellX - dp(1f),
                        rowTop + dp(2f),
                        asciiCellX + asciiCharW + dp(1f),
                        rowTop + rowHeight - dp(2f),
                        selectionPaint
                    )
                }

                canvas.drawText(hexTable[value], cellX, rowTop + baseline, if (printable) hexPaint else hexDimPaint)
                canvas.drawText(
                    asciiTable[value],
                    asciiCellX,
                    rowTop + baseline,
                    if (printable) asciiPaint else asciiDimPaint
                )
            }

            canvas.drawText("|", asciiStart + asciiColW + dp(2f), rowTop + baseline, separatorPaint)
        }

        canvas.restore()
        drawScrollbars(canvas)
    }

    private fun hexOffset(value: Long): String {
        val chars = CharArray(8)
        var v = value
        for (i in 7 downTo 0) {
            chars[i] = HEX_CHARS[(v and 0xFL).toInt()]
            v = v ushr 4
        }
        return String(chars)
    }

    private fun baselineInRow(): Float {
        val fm = offsetPaint.fontMetrics
        return (rowHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    private fun drawScrollbars(canvas: Canvas) {
        val barW = dp(3f)
        val totalH = contentH * scaleFactor
        val totalW = contentW * scaleFactor
        if (maxScrollY > 0f) {
            val trackH = height.toFloat()
            val thumbH = (trackH / totalH * trackH).coerceIn(dp(24f), trackH)
            val thumbY = (scrollY / maxScrollY) * (trackH - thumbH)
            canvas.drawRect(width - barW, thumbY, width.toFloat(), thumbY + thumbH, scrollbarPaint)
        }
        if (maxScrollX > 0f) {
            val trackW = width.toFloat()
            val thumbW = (trackW / totalW * trackW).coerceIn(dp(24f), trackW)
            val thumbX = (scrollX / maxScrollX) * (trackW - thumbW)
            canvas.drawRect(thumbX, height - barW, thumbX + thumbW, height.toFloat(), scrollbarPaint)
        }
    }

    private fun resolveColor(attr: Int, defaultColor: Int = 0): Int {
        return MaterialColors.getColor(context, attr, defaultColor)
    }
}
