package com.foxluo.pdf.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import com.blankj.utilcode.util.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap

/**
 * 文字贴纸容器视图，用于在图像上添加和编辑文字
 */
class TextStickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {
    // 图像显示组件
    private val imageView: ImageView
    private var originalBitmap: Bitmap? = null
    private var displayMatrix = Matrix()
    private var inverseMatrix = Matrix()
    private var isImageLoaded = false

    // 文字输入视图列表
    private val textInputViews = mutableListOf<DraggableTextInputView>()
    private var currentTextInputView: DraggableTextInputView? = null

    // 协程作用域
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    init {
        // 初始化图像显示组件
        imageView = ImageView(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.MATRIX
            setOnClickListener { showTextInputAt(it.x, it.y) }
        }
        addView(imageView)
    }

    /**
     * 设置原始图像
     */
    fun setOriginalImage(bitmap: Bitmap) {
        originalBitmap?.recycle()
        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        isImageLoaded = true
        adjustImageMatrix()
    }

    /**
     * 调整图像矩阵以适应视图
     */
    private fun adjustImageMatrix() {
        originalBitmap?.let {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val imgWidth = it.width.toFloat()
            val imgHeight = it.height.toFloat()

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
            imageView.imageMatrix = displayMatrix

            // 计算逆矩阵用于坐标转换
            displayMatrix.invert(inverseMatrix)
        }
    }

    /**
     * 在指定位置显示文字输入框
     */
    private fun showTextInputAt(x: Float, y: Float) {
        if (!isImageLoaded) return

        currentTextInputView?.let { removeView(it) }

        currentTextInputView = DraggableTextInputView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
            this.x = x - width / 2
            this.y = y - height / 2
            setOnCloseListener { removeView(it) }
            setOnTextChangeListener { invalidate() }
        }

        currentTextInputView?.let {
            addView(it)
            textInputViews.add(it)
            it.requestFocus()
        }
    }

    /**
     * 获取添加文字后的图像
     */
    fun getImageWithText(): Bitmap? {
        return originalBitmap?.copy(Bitmap.Config.ARGB_8888, true)?.apply {
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            textInputViews.forEach { textView ->
                // 将视图坐标转换为图像坐标
                val viewPoints = floatArrayOf(textView.x, textView.y)
                inverseMatrix.mapPoints(viewPoints)

                // 绘制文字到图像
                val textPaint = textView.textPaint
                val text = textView.text
                if (text.isNotEmpty()) {
                    canvas.drawText(text, viewPoints[0], viewPoints[1], textPaint)
                }
            }
        }
    }

    /**
     * 使用OpenCV将文字绘制到图像
     */
    fun getCurrentImage(callback: (Bitmap?) -> Unit) {
        originalBitmap?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val mat = Mat()
                    Utils.bitmapToMat(it, mat)

                    textInputViews.forEach { textView ->
                        // 获取文字图像
                        val textBitmap = textView.getBitmap()
                        val textMat = Mat()
                        Utils.bitmapToMat(textBitmap, textMat)

                        // 计算文字在原图上的位置和变换
                        val viewCenterX = textView.x + textView.width / 2
                        val viewCenterY = textView.y + textView.height / 2
                        val viewPoints = floatArrayOf(viewCenterX, viewCenterY)
                        inverseMatrix.mapPoints(viewPoints)
                        val imgCenterX = viewPoints[0]
                        val imgCenterY = viewPoints[1]

                        // 计算旋转和缩放矩阵
                        val rotationMatrix = Imgproc.getRotationMatrix2D(
                            Point(imgCenterX.toDouble(), imgCenterY.toDouble()),
                            textView.rotation.toDouble(),
                            textView.scaleX.toDouble()
                        )

                        // 调整文字图像大小和旋转
                        val rotatedTextMat = Mat()
                        Imgproc.warpAffine(
                            textMat,
                            rotatedTextMat,
                            rotationMatrix,
                            Size(
                                (textMat.cols() * textView.scaleX).toDouble(),
                                (textMat.rows() * textView.scaleY).toDouble()
                            )
                        )

                        // 计算文字在原图上的位置（考虑旋转中心）
                        val x = imgCenterX - rotatedTextMat.cols() / 2.0
                        val y = imgCenterY - rotatedTextMat.rows() / 2.0

                        // 确保文字绘制在图像范围内
                        if (x >= 0 && y >= 0 && x + rotatedTextMat.cols() <= mat.cols() && y + rotatedTextMat.rows() <= mat.rows()) {
                            val roi = Mat(
                                mat, org.opencv.core.Rect(
                                    x.toInt(),
                                    y.toInt(),
                                    rotatedTextMat.cols(),
                                    rotatedTextMat.rows()
                                )
                            )
                            rotatedTextMat.copyTo(roi)
                            roi.release()
                        }

                        textMat.release()
                        rotatedTextMat.release()
                    }

                    val resultBitmap =
                        createBitmap(mat.cols(), mat.rows())
                    Utils.matToBitmap(mat, resultBitmap)
                    mat.release()

                    withContext(Dispatchers.Main) {
                        callback(resultBitmap)
                    }
                } catch (e: Exception) {
                    LogUtils.e("TextStickerViewGroup", "Error processing image with OpenCV", e)
                    withContext(Dispatchers.Main) {
                        callback(null)
                    }
                }
            }
        } ?: run {
            callback(null)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) {
            adjustImageMatrix()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 传递触摸事件给文字输入视图
        return (currentTextInputView?.onTouchEvent(event) == true) || super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        originalBitmap?.recycle()
        originalBitmap = null
        coroutineScope.cancel()
    }
}