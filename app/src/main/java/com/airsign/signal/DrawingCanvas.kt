package com.airsign.signal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class DrawingCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val paint = Paint().apply {
        color = Color.parseColor("#00f0ff")
        isAntiAlias = true
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        // Neon glow effect (disabled on hardware acceleration if unsupported, but works standard)
        setShadowLayer(15f, 0f, 0f, Color.parseColor("#00f0ff"))
    }

    private var drawingListener: DrawingListener? = null
    private val pointsHistory = ArrayList<PointF>()
    private var lastX = 0f
    private var lastY = 0f

    interface DrawingListener {
        fun onDrawStart()
        fun onDrawing(x: Float, y: Float, speedX: Float, speedY: Float)
        fun onDrawEnd(points: List<PointF>)
    }

    fun setDrawingListener(listener: DrawingListener) {
        this.drawingListener = listener
    }

    fun clearCanvas() {
        path.reset()
        pointsHistory.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                lastX = x
                lastY = y
                pointsHistory.clear()
                pointsHistory.add(PointF(x, y))
                drawingListener?.onDrawStart()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)
                if (dx >= 4 || dy >= 4) {
                    path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    
                    val speedX = x - lastX
                    val speedY = y - lastY
                    
                    lastX = x
                    lastY = y
                    pointsHistory.add(PointF(x, y))
                    
                    drawingListener?.onDrawing(x, y, speedX, speedY)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                path.lineTo(x, y)
                pointsHistory.add(PointF(x, y))
                drawingListener?.onDrawEnd(ArrayList(pointsHistory))
                invalidate()
            }
        }
        return true
    }
}
