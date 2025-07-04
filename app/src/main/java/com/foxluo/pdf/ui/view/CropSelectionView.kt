package com.foxluo.pdf.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.util.Log


/**
 * 裁剪框的选择View
 */
class CropSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var magnifierView: MagnifierView? = null
    private val selectionPath = Path()
    companion object{
        // 常量定义
        private const val POINT_RADIUS = 20f
        private const val LINE_WIDTH = 3f
        private const val CONTROL_POINT_RADIUS = 15f
        private const val SELECTION_TOLERANCE = 30f

    }
    // 内部类用于表示坐标点
    data class Point(val x: Float, val y: Float)
    // 画笔定义
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH
    }
    private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val curvePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 255, 0)
        style = Paint.Style.FILL
    }

    // 坐标点数据
    private var _vertices = mutableListOf<Point>()
    private var curvePoints = mutableListOf<Point>()
    private var draggedPointIndex: Int? = null
    private var isCurvePoint = false

    // 回调接口
    var onPointsChanged: ((vertices: List<Point>, curvePoints: List<Point>) -> Unit)? = null

    init {
        // 延迟初始化默认坐标，等待视图尺寸确定
    }

    /**
     * 设置默认坐标点
     */
    internal fun setupDefaultPoints() {
        _vertices.clear()
        curvePoints.clear()

        // 默认矩形四个顶点
        val margin = 100f
        val right = width - margin
        val bottom = height - margin

        _vertices.add(Point(margin, margin))           // 左上
        _vertices.add(Point(right, margin))            // 右上
        _vertices.add(Point(right, bottom))            // 右下
        _vertices.add(Point(margin, bottom))           // 左下

        // 默认弯曲点（中点）
        curvePoints.add(Point((margin + right)/2, margin))       // 上中点
        curvePoints.add(Point(right, (margin + bottom)/2))       // 右中点
        curvePoints.add(Point((margin + right)/2, bottom))       // 下中点
        curvePoints.add(Point(margin, (margin + bottom)/2))      // 左中点
    }

    /**
     * 设置顶点坐标
     */
    fun setVertices(newVertices: List<Point>) {
        if (newVertices.size != 4) {
            throw IllegalArgumentException("必须提供4个顶点坐标")
        }
        _vertices.clear()
        _vertices.addAll(newVertices)
        updateCurvePointsRange()
        invalidate()
    }

    /**
     * 设置弯曲点坐标
     */
    fun setCurvePoints(newCurvePoints: List<Point>) {
        if (newCurvePoints.size != 4) {
            throw IllegalArgumentException("必须提供4个弯曲点坐标")
        }
        curvePoints.clear()
        curvePoints.addAll(newCurvePoints)
        invalidate()
    }

    /**
     * 更新弯曲点的可拖动范围
     */
    private fun updateCurvePointsRange() {
        for (i in curvePoints.indices) {
            adjustCurvePointIfOutOfRange(i)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (_vertices.isEmpty() || oldw == 0 || oldh == 0) {
            setupDefaultPoints()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制选择区域
        drawSelectionArea(canvas)

        // 绘制连接线
        drawLines(canvas)

        // 绘制顶点
        drawVertices(canvas)

        // 绘制弯曲点
        drawCurvePoints(canvas)
    }

    /**
     * 绘制选择区域
     */
    private fun drawSelectionArea(canvas: Canvas) {
        if (_vertices.size < 4) return

        selectionPath.reset()
        selectionPath.moveTo(_vertices[0].x, _vertices[0].y)

        // 使用贝塞尔曲线连接各点
        selectionPath.quadTo(curvePoints[0].x, curvePoints[0].y, _vertices[1].x, _vertices[1].y)
        selectionPath.quadTo(curvePoints[1].x, curvePoints[1].y, _vertices[2].x, _vertices[2].y)
        selectionPath.quadTo(curvePoints[2].x, curvePoints[2].y, _vertices[3].x, _vertices[3].y)
        selectionPath.quadTo(curvePoints[3].x, curvePoints[3].y, _vertices[0].x, _vertices[0].y)

        canvas.drawPath(selectionPath, areaPaint)
        canvas.drawPath(selectionPath, mainPaint)
    }

    /**
     * 绘制连接线
     */
    private fun drawLines(canvas: Canvas) {
        if (_vertices.size < 4 || curvePoints.size < 4) return

        // 绘制顶点与弯曲点之间的辅助线
        val helperPaint = Paint(mainPaint).apply {
            color = Color.LTGRAY
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        for (i in 0 until 4) {
            val nextVertex = _vertices[(i + 1) % 4]
            canvas.drawLine(_vertices[i].x, _vertices[i].y, curvePoints[i].x, curvePoints[i].y, helperPaint)
            canvas.drawLine(curvePoints[i].x, curvePoints[i].y, nextVertex.x, nextVertex.y, helperPaint)
        }
    }

    /**
     * 绘制顶点
     */
    private fun drawVertices(canvas: Canvas) {
        _vertices.forEach {
            canvas.drawCircle(it.x, it.y, POINT_RADIUS, vertexPaint)
        }
    }

    /**
     * 绘制弯曲点
     */
    private fun drawCurvePoints(canvas: Canvas) {
        curvePoints.forEach {
            canvas.drawCircle(it.x, it.y, CONTROL_POINT_RADIUS, curvePointPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggedPointIndex = findSelectedPoint(event.x, event.y)
                if (draggedPointIndex != null) {
                    magnifierView?.visibility = View.VISIBLE
                }
                return draggedPointIndex != null
            }
            MotionEvent.ACTION_MOVE -> {
                draggedPointIndex?.let { index ->
                    val newX = event.x
                    val newY = event.y

                    // 限制弯曲点拖动范围
                    if (isCurvePoint) {
                        val curveRange = getCurvePointRange(index)
                        val minX = curveRange.left
                        val maxX = curveRange.right
                        val minY = curveRange.top
                        val maxY = curveRange.bottom
                        val clampedX = newX.coerceIn(minX, maxX)
                        val clampedY = newY.coerceIn(minY, maxY)
                        curvePoints[index] = Point(clampedX, clampedY)
                    } else {
                        _vertices[index] = Point(newX, newY)
                        // 更新相关弯曲点的范围
                        updateAffectedCurvePoints(index)
                    }

                    invalidate()
                    updateMagnifier(newX, newY)
                    // 回调坐标变化
                    onPointsChanged?.invoke(_vertices.toList(), curvePoints.toList())
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                draggedPointIndex = null
                magnifierView?.visibility = View.GONE
            }
        }
        return true
    }

    /**
     * 查找被选中的点
     */
    private fun findSelectedPoint(x: Float, y: Float): Int? {
        // 先检查弯曲点
        curvePoints.forEachIndexed { index, point ->
            if (distanceSquared(x, y, point.x, point.y) <= SELECTION_TOLERANCE * SELECTION_TOLERANCE) {
                isCurvePoint = true
                return index
            }
        }

        // 再检查顶点
        _vertices.forEachIndexed { index, point ->
            if (distanceSquared(x, y, point.x, point.y) <= SELECTION_TOLERANCE) {
                isCurvePoint = false
                return index
            }
        }

        return null
    }

    /**
     * 计算两点间距离
     */
    /**
     * 计算两点间距离的平方（避免平方根运算，提高性能）
     */
    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    /**
     * 获取弯曲点的拖动范围
     */
    private fun getCurvePointRange(curvePointIndex: Int): RectF {
        val startVertex = _vertices[curvePointIndex]
        val endVertex = _vertices[(curvePointIndex + 1) % 4]

        return RectF(
            startVertex.x.coerceAtMost(endVertex.x),
            startVertex.y.coerceAtMost(endVertex.y),
            startVertex.x.coerceAtLeast(endVertex.x),
            startVertex.y.coerceAtLeast(endVertex.y)
        )
    }

    /**
     * 更新受顶点影响的弯曲点
     */
    private fun updateAffectedCurvePoints(vertexIndex: Int) {
        // 更新与该顶点相关的两个弯曲点
        val prevCurveIndex = (vertexIndex - 1 + 4) % 4
        val nextCurveIndex = vertexIndex

        // 检查弯曲点是否超出新范围，如果是则调整
        adjustCurvePointIfOutOfRange(prevCurveIndex)
        adjustCurvePointIfOutOfRange(nextCurveIndex)
    }

    /**
     * 如果弯曲点超出范围则调整
     */
    private fun adjustCurvePointIfOutOfRange(curvePointIndex: Int) {
        val range = getCurvePointRange(curvePointIndex)
        val curvePoint = curvePoints[curvePointIndex]

        val clampedX = curvePoint.x.coerceIn(range.left, range.right)
        val clampedY = curvePoint.y.coerceIn(range.top, range.bottom)

        if (clampedX != curvePoint.x || clampedY != curvePoint.y) {
            curvePoints[curvePointIndex] = Point(clampedX, clampedY)
        }
    }

    /**
     * 设置放大镜视图
     */
    fun setMagnifierView(magnifier: MagnifierView) {
        this.magnifierView = magnifier
        // 设置初始图像
        (background as? BitmapDrawable)?.bitmap?.let {
            magnifier.setSourceImage(it)
        } ?: run {
            Log.w("CropSelectionView", "背景不是BitmapDrawable，无法设置放大镜源图像")
        }
    }

    /**
     * 更新放大镜显示内容
     */
    private fun updateMagnifier(x: Float, y: Float) {
        magnifierView?.let {
            if(it.visibility != View.VISIBLE){
                return
            }
            val magnifierSize = 200f
            val targetRect = RectF(
                x - magnifierSize / 2,
                y - magnifierSize / 2,
                x + magnifierSize / 2,
                y + magnifierSize / 2
            )
            // 限制在视图范围内
            targetRect.intersect(0f, 0f, width.toFloat(), height.toFloat())
            it.setTargetRect(targetRect)
            it.setCropPaths(listOf(selectionPath))
            it.update()
        }
    }

    fun getVertices() = _vertices

    fun getCurrentPoints() = curvePoints
}