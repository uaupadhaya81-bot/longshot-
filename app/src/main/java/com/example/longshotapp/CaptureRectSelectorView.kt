package com.example.longshotapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CaptureRectSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val frame = RectF()

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88000000.toInt()
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private val minSize = dp(160f)
    private val handleRadius = dp(14f)
    private val touchSlop = dp(24f)

    private enum class DragMode {
        NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (frame.isEmpty) {
            val left = w * 0.10f
            val top = h * 0.18f
            val right = w * 0.90f
            val bottom = h * 0.80f
            frame.set(left, top, right, bottom)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Darken everything outside the selected rectangle.
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, scrimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, scrimPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, scrimPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), scrimPaint)

        // Border
        canvas.drawRect(frame, borderPaint)

        // Corner handles
        drawHandle(canvas, frame.left, frame.top)
        drawHandle(canvas, frame.right, frame.top)
        drawHandle(canvas, frame.left, frame.bottom)
        drawHandle(canvas, frame.right, frame.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handleStrokePaint)
        canvas.drawCircle(x, y, handleRadius * 0.72f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragMode = hitTest(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                when (dragMode) {
                    DragMode.MOVE -> moveFrame(dx, dy)
                    DragMode.TOP_LEFT -> resizeTopLeft(dx, dy)
                    DragMode.TOP_RIGHT -> resizeTopRight(dx, dy)
                    DragMode.BOTTOM_LEFT -> resizeBottomLeft(dx, dy)
                    DragMode.BOTTOM_RIGHT -> resizeBottomRight(dx, dy)
                    DragMode.NONE -> Unit
                }

                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    fun getFrameRect(): Rect {
        return Rect(
            frame.left.roundToInt(),
            frame.top.roundToInt(),
            frame.right.roundToInt(),
            frame.bottom.roundToInt()
        )
    }

    fun setFrameRect(rect: Rect) {
        frame.set(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat()
        )
        clampToBounds()
        invalidate()
    }

    private fun hitTest(x: Float, y: Float): DragMode {
        if (distance(x, y, frame.left, frame.top) <= touchSlop) return DragMode.TOP_LEFT
        if (distance(x, y, frame.right, frame.top) <= touchSlop) return DragMode.TOP_RIGHT
        if (distance(x, y, frame.left, frame.bottom) <= touchSlop) return DragMode.BOTTOM_LEFT
        if (distance(x, y, frame.right, frame.bottom) <= touchSlop) return DragMode.BOTTOM_RIGHT
        if (frame.contains(x, y)) return DragMode.MOVE
        return DragMode.NONE
    }

    private fun moveFrame(dx: Float, dy: Float) {
        frame.offset(dx, dy)
        clampToBounds()
    }

    private fun resizeTopLeft(dx: Float, dy: Float) {
        frame.left += dx
        frame.top += dy
        enforceMinSize(left = true, top = true)
        clampToBounds()
    }

    private fun resizeTopRight(dx: Float, dy: Float) {
        frame.right += dx
        frame.top += dy
        enforceMinSize(right = true, top = true)
        clampToBounds()
    }

    private fun resizeBottomLeft(dx: Float, dy: Float) {
        frame.left += dx
        frame.bottom += dy
        enforceMinSize(left = true, bottom = true)
        clampToBounds()
    }

    private fun resizeBottomRight(dx: Float, dy: Float) {
        frame.right += dx
        frame.bottom += dy
        enforceMinSize(right = true, bottom = true)
        clampToBounds()
    }

    private fun enforceMinSize(
        left: Boolean = false,
        top: Boolean = false,
        right: Boolean = false,
        bottom: Boolean = false
    ) {
        if (frame.width() < minSize) {
            if (left) frame.left = frame.right - minSize
            if (right) frame.right = frame.left + minSize
        }
        if (frame.height() < minSize) {
            if (top) frame.top = frame.bottom - minSize
            if (bottom) frame.bottom = frame.top + minSize
        }
    }

    private fun clampToBounds() {
        val maxW = width.toFloat()
        val maxH = height.toFloat()

        if (frame.left < 0f) {
            val diff = -frame.left
            frame.offset(diff, 0f)
        }
        if (frame.top < 0f) {
            val diff = -frame.top
            frame.offset(0f, diff)
        }
        if (frame.right > maxW) {
            val diff = frame.right - maxW
            frame.offset(-diff, 0f)
        }
        if (frame.bottom > maxH) {
            val diff = frame.bottom - maxH
            frame.offset(0f, -diff)
        }

        frame.left = frame.left.coerceAtLeast(0f)
        frame.top = frame.top.coerceAtLeast(0f)
        frame.right = frame.right.coerceAtMost(maxW)
        frame.bottom = frame.bottom.coerceAtMost(maxH)

        if (frame.width() < minSize) frame.right = min(frame.left + minSize, maxW)
        if (frame.height() < minSize) frame.bottom = min(frame.top + minSize, maxH)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
