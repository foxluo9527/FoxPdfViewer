package com.foxluo.pdf.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.IntRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.cancel
import java.util.Stack

/**
 * 涂鸦容器视图，分离原图与绘制层，支持橡皮擦仅擦除绘制内容
 */
@SuppressLint("ClickableViewAccessibility")
class DoodleContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    // 原图显示组件
    // 初始化原图显示组件
    private val originalImageView by lazy {
        ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.MATRIX
        }
    }

    // 绘制层视图
    private val drawingView by lazy {
        DrawingView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnTouchListener {_, event ->
                // 转换触摸坐标到绘制层
                val imageCords = convertToImageCoords(event.x, event.y)
                val drawingEvent = MotionEvent.obtain(event)
                drawingEvent.setLocation(imageCords.x, imageCords.y)
                dispatchTouchEvent(drawingEvent)
                true
            }
        }
    }

    // 原始图像
    private var originalBitmap: Bitmap? = null
    // 显示矩阵
    private val displayMatrix = Matrix()
    // 逆矩阵（用于坐标转换）
    private val inverseMatrix = Matrix()
    // 协程作用域
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    init {
        addView(originalImageView)
        addView(drawingView)
    }

    /**
     * 设置原始图像
     */
    fun setOriginalImage(bitmap: Bitmap) {
        originalBitmap?.recycle()
        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        adjustImageMatrix()
        drawingView.setDrawingSize(bitmap.width, bitmap.height)
    }

    /**
     * 调整图像矩阵以适应视图
     */
    private fun adjustImageMatrix() {
        originalBitmap?.let { bitmap ->
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val imgWidth = bitmap.width.toFloat()
            val imgHeight = bitmap.height.toFloat()

            // 计算缩放比例
            val scaleX = viewWidth / imgWidth
            val scaleY = viewHeight / imgHeight
            val scale = minOf(scaleX, scaleY)

            // 计算居中偏移
            val translateX = (viewWidth - imgWidth * scale) / 2
            val translateY = (viewHeight - imgHeight * scale) / 2

            // 设置矩阵
            displayMatrix.reset()
            displayMatrix.postScale(scale, scale)
            displayMatrix.postTranslate(translateX, translateY)
            originalImageView.imageMatrix = displayMatrix
            displayMatrix.invert(inverseMatrix)
        }
    }

    /**
     * 将视图坐标转换为图像坐标
     */
    private fun convertToImageCoords(x: Float, y: Float): PointF {
        val points = floatArrayOf(x, y)
        inverseMatrix.mapPoints(points)
        return PointF(points[0], points[1])
    }

    /**
     * 设置画笔颜色
     */
    fun setBrushColor(@IntRange(from = 0) color: Int) {
        drawingView.brushColor = color
    }

    /**
     * 设置画笔大小
     */
    fun setBrushSize(size: Float) {
        drawingView.brushSize = size
    }

    /**
     * 设置橡皮擦大小
     */
    fun setEraserSize(size: Float) {
        drawingView.eraserSize = size
    }

    /**
     * 切换橡皮擦模式
     */
    fun setEraserMode(enabled: Boolean) {
        drawingView.isEraserMode = enabled
    }

    /**
     * 撤销操作
     */
    fun undo() {
        drawingView.undo()
    }

    /**
     * 恢复操作
     */
    fun redo() {
        drawingView.redo()
    }

    /**
     * 获取合并后的图像（使用OpenCV）
     */
    fun getMergedImage(callback: (Bitmap?) -> Unit) {
        originalBitmap?.let { original ->
            coroutineScope.launch(Dispatchers.IO) {
                val originalMat = Mat()
                val drawingMat = Mat()
                val resultMat = Mat()
                val mask = Mat()
                var drawingBitmap: Bitmap? = null
                try {
                    // 将原图转换为Mat
                    Utils.bitmapToMat(original, originalMat)

                    // 获取绘制层Bitmap并转换为Mat
                    drawingBitmap = drawingView.getDrawingBitmap()
                    Utils.bitmapToMat(drawingBitmap, drawingMat)

                    // 创建结果Mat
                    originalMat.copyTo(resultMat)

                    // 只合并绘制层中非透明区域
                    Imgproc.cvtColor(drawingMat, mask, Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.threshold(mask, mask, 1.0, 255.0, Imgproc.THRESH_BINARY)

                    // 复制绘制内容到原图
                    drawingMat.copyTo(resultMat, mask)

                    // 转换回Bitmap
                    val resultBitmap = createBitmap(original.width, original.height)
                    Utils.matToBitmap(resultMat, resultBitmap)

                    withContext(Dispatchers.Main) {
                        callback(resultBitmap)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        callback(null)
                    }
                } finally {
                    originalMat.release()
                    drawingMat.release()
                    resultMat.release()
                    mask.release()
                    drawingBitmap?.recycle()
                }
            }
        } ?: run {
            callback(null)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        originalBitmap?.recycle()
        originalBitmap = null
        drawingView.release()
        coroutineScope.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        adjustImageMatrix()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val childLeft = 0
        val childTop = 0
        val childWidth = r - l
        val childHeight = b - t
        originalImageView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        drawingView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = measuredWidth
        val height = measuredHeight
        originalImageView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        drawingView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    /**
     * 内部绘制视图，负责处理绘制和橡皮擦逻辑
     */
    class DrawingView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {
        // 绘制路径列表
        private val pathList = mutableListOf<DrawingPath>()
        // 当前绘制路径
        private var currentPath: DrawingPath? = null
        // 当前路径列表
        private val currentPaths = mutableListOf<DrawingPath>()
        // 撤销栈
        private val undoStack = Stack<List<DrawingPath>>()
        // 恢复栈
        private val redoStack = Stack<List<DrawingPath>>()
        // 画笔
        private val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.BLACK
            strokeWidth = 10f
        }
        // 绘制缓存
        private var drawingBitmap: Bitmap? = null
        private var drawingCanvas: Canvas? = null
        // 画笔颜色
        var brushColor: Int
            get() = paint.color
            set(value) { paint.color = value }
        // 画笔粗细
        var brushSize: Float
            get() = paint.strokeWidth
            set(value) { paint.strokeWidth = value }
        // 橡皮擦粗细
        var eraserSize: Float = 20f
        // 是否为橡皮擦模式
        var isEraserMode = false
            set(value) {
                field = value
                paint.xfermode = if (value) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
                paint.strokeWidth = if (value) eraserSize else brushSize
            }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        /**
         * 设置绘制区域大小
         */
        fun setDrawingSize(width: Int, height: Int) {
            drawingBitmap?.recycle()
            drawingBitmap = createBitmap(width, height)
            drawingCanvas = Canvas(drawingBitmap!!)
            drawingCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            pathList.clear()
            undoStack.clear()
            redoStack.clear()
            invalidate()
        }

        /**
         * 获取绘制层Bitmap
         */
        fun getDrawingBitmap(): Bitmap {
            return drawingBitmap?.copy(Bitmap.Config.ARGB_8888, true) ?: createBitmap(1, 1)
        }

        /**
         * 撤销操作
         */
        fun undo() {
                if (undoStack.isNotEmpty()) {
                    redoStack.push(currentPaths.map { it.copy() })
                    currentPaths.clear()
                    currentPaths.addAll(undoStack.pop().map { it.copy() })
                    redrawCache()
                }
            }

        /**
         * 恢复操作
         */
        fun redo() {
                if (redoStack.isNotEmpty()) {
                    undoStack.push(currentPaths.map { it.copy() })
                    currentPaths.clear()
                    currentPaths.addAll(redoStack.pop().map { it.copy() })
                    redrawCache()
                }
            }

        /**
         * 重绘缓存
         */
        private fun redrawCache() {
            drawingCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            currentPaths.forEach { drawPath(it) }
            invalidate()
        }

        /**
         * 绘制路径
         */
        private fun drawPath(path: DrawingPath) {
            val oldColor = paint.color
            val oldWidth = paint.strokeWidth
            val oldXfermode = paint.xfermode

            paint.color = path.color
            paint.strokeWidth = path.strokeWidth
            paint.xfermode = if (path.isEraser) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            drawingCanvas?.drawPath(path.path, paint)

            // 恢复画笔状态
            paint.color = oldColor
            paint.strokeWidth = oldWidth
            paint.xfermode = oldXfermode
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            currentPath?.let { canvas.drawPath(it.path, paint) }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPath = DrawingPath(
                        color = brushColor,
                        strokeWidth = if (isEraserMode) eraserSize else brushSize,
                        isEraser = isEraserMode
                    )
                    currentPath?.path?.moveTo(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentPath?.path?.lineTo(event.x, event.y)
                    postInvalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    currentPath?.let {
                        val newPath = it.copy()
                        val newPathList = currentPaths.map { it.copy() }.toMutableList().apply { add(newPath) }
                        undoStack.push(currentPaths.map { it.copy() })
                        currentPaths.clear()
                        currentPaths.addAll(newPathList)
                        redoStack.clear()
                        drawPath(newPath)
                    }
                    currentPath = null
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    currentPath = null
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /**
         * 释放资源
         */
        fun release() {
            drawingBitmap?.recycle()
            drawingBitmap = null
            drawingCanvas = null
        }

        /**
         * 绘制路径数据类
         */
        private data class DrawingPath(
            val path: Path = Path(),
            val color: Int,
            val strokeWidth: Float,
            val isEraser: Boolean
        ) {
            // 深拷贝路径
            fun copy(): DrawingPath {
                return DrawingPath(
                    path = Path(path),
                    color = color,
                    strokeWidth = strokeWidth,
                    isEraser = isEraser
                )
            }
        }
    }
}