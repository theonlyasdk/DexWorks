package com.asdk.tools.dexworks

import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DragSelectTouchListener(
    private val onSelectRange: (startPosition: Int, endPosition: Int) -> Unit,
    private val onDragEnded: (() -> Unit)? = null
) : RecyclerView.OnItemTouchListener {

    private var recyclerView: RecyclerView? = null
    var isDragging: Boolean = false
        private set

    private var startPosition: Int = RecyclerView.NO_POSITION
    private var lastPosition: Int = RecyclerView.NO_POSITION
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var scrollDistance: Int = 0
    private var isAutoScrolling: Boolean = false

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val rv = recyclerView ?: return
            if (!isDragging) {
                isAutoScrolling = false
                return
            }
            if (scrollDistance != 0) {
                rv.scrollBy(0, scrollDistance)
                checkCurrentPosition(rv, lastX, lastY)
                rv.postOnAnimation(this)
            } else {
                isAutoScrolling = false
            }
        }
    }

    fun attachToRecyclerView(rv: RecyclerView) {
        recyclerView = rv
        rv.addOnItemTouchListener(this)
    }

    fun startDragSelection(position: Int) {
        val rv = recyclerView ?: return
        if (position == RecyclerView.NO_POSITION) return
        isDragging = true
        startPosition = position
        lastPosition = position

        val child = rv.findViewHolderForAdapterPosition(position)?.itemView
        if (child != null) {
            lastX = child.x + child.width / 2f
            lastY = child.y + child.height / 2f
        } else {
            lastX = rv.width / 2f
            lastY = rv.height / 2f
        }

        rv.requestDisallowInterceptTouchEvent(false)
        rv.parent?.requestDisallowInterceptTouchEvent(true)
    }

    fun stopDrag() {
        if (!isDragging) return
        isDragging = false
        isAutoScrolling = false
        val rv = recyclerView
        rv?.removeCallbacks(autoScrollRunnable)
        rv?.parent?.requestDisallowInterceptTouchEvent(false)
        startPosition = RecyclerView.NO_POSITION
        lastPosition = RecyclerView.NO_POSITION
        scrollDistance = 0
        onDragEnded?.invoke()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!isDragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return false
            MotionEvent.ACTION_MOVE -> {
                handleMove(rv, e)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopDrag()
                return false
            }
        }
        return isDragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!isDragging) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                handleMove(rv, e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopDrag()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Ignored to maintain drag selection control
    }

    private fun handleMove(rv: RecyclerView, e: MotionEvent) {
        lastX = e.x
        lastY = e.y
        checkCurrentPosition(rv, e.x, e.y)
        checkAutoScroll(rv, e.y)
    }

    private fun checkCurrentPosition(rv: RecyclerView, x: Float, y: Float) {
        val child = rv.findChildViewUnder(x, y)
        val pos = if (child != null) {
            rv.getChildAdapterPosition(child)
        } else {
            val lm = rv.layoutManager as? LinearLayoutManager
            if (y <= 0f) {
                lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            } else if (y >= rv.height) {
                lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
            } else {
                findNearestPosition(rv, y)
            }
        }

        if (pos != RecyclerView.NO_POSITION && pos != lastPosition) {
            lastPosition = pos
            onSelectRange(startPosition, pos)
        }
    }

    private fun findNearestPosition(rv: RecyclerView, y: Float): Int {
        val count = rv.childCount
        for (i in 0 until count) {
            val child = rv.getChildAt(i)
            if (y >= child.top && y <= child.bottom) {
                return rv.getChildAdapterPosition(child)
            }
        }
        return RecyclerView.NO_POSITION
    }

    private fun checkAutoScroll(rv: RecyclerView, y: Float) {
        val hotspot = rv.context.dp(64)
        val maxSpeed = rv.context.dp(18)

        val canScrollUp = rv.canScrollVertically(-1)
        val canScrollDown = rv.canScrollVertically(1)

        scrollDistance = when {
            y < hotspot && canScrollUp -> {
                val factor = ((hotspot - y) / hotspot).coerceIn(0f, 2f)
                (-maxSpeed * factor).toInt().coerceAtMost(-1)
            }
            y > rv.height - hotspot && canScrollDown -> {
                val factor = ((y - (rv.height - hotspot)) / hotspot).coerceIn(0f, 2f)
                (maxSpeed * factor).toInt().coerceAtLeast(1)
            }
            else -> 0
        }

        if (scrollDistance != 0) {
            if (!isAutoScrolling) {
                isAutoScrolling = true
                rv.postOnAnimation(autoScrollRunnable)
            }
        } else {
            if (isAutoScrolling) {
                isAutoScrolling = false
                rv.removeCallbacks(autoScrollRunnable)
            }
        }
    }
}
