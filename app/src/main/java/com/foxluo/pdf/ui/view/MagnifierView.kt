package com.foxluo.pdf.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 裁剪框的放大器View，需与裁剪view绑定
 */
class MagnifierView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var sourceBitmap: Bitmap? = null
    private val matrix = Matrix()
    private val scaledPath = Path()
    private var targetRect: RectF? = null
    private var cropPaths: List<Path>? = null
    private var zoomFactor: Float = 2.0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    init {
        holder.addCallback(this)
    }

    fun setSourceImage(bitmap: Bitmap) {
        // 回收旧的Bitmap
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        val config = bitmap.config ?: return
        sourceBitmap = bitmap.copy(config, true)
    }

    fun setZoomFactor(factor: Float) {
        zoomFactor = factor.coerceIn(1.0f, 4.0f) // 限制缩放范围1.0-4.0
    }

    fun getZoomFactor(): Float = zoomFactor

    fun setTargetRect(rect: RectF) {
        targetRect = rect
        update()
    }

    fun setCropPaths(paths: List<Path>) {
        cropPaths = paths
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawMagnifiedArea()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawMagnifiedArea()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 释放资源
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        sourceBitmap = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 视图分离时释放资源
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        sourceBitmap = null
    }

    fun update() {
        drawMagnifiedArea()
    }

    private fun drawMagnifiedArea() {
        val canvas = holder.lockCanvas()
        canvas?.let {
            canvas.drawColor(Color.WHITE)
            sourceBitmap?.let { bitmap ->
                targetRect?.let { rect ->
                    // 计算放大区域和目标区域
                    val srcRect = RectF(rect)
                    val dstRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

                    // 重置并使用成员变量matrix提高性能
                    matrix.reset()
                    matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.CENTER)
                    matrix.postScale(zoomFactor, zoomFactor, width / 2f, height / 2f)

                    // 绘制放大图像
                    canvas.drawBitmap(bitmap, matrix, paint)

                    // 绘制裁剪路径
                    cropPaths?.forEach { path ->
                        scaledPath.reset()
                        scaledPath.addPath(path)
                        scaledPath.transform(matrix)
                        canvas.drawPath(scaledPath, dashPaint)
                    }
                }
            }
            holder.unlockCanvasAndPost(canvas)
        }
    }
}