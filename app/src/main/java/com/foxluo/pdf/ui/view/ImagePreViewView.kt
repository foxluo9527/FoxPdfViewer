package com.foxluo.pdf.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc
import org.opencv.core.Point
import org.opencv.core.Size
import com.foxluo.pdf.util.ImageFilterProcessor
import com.foxluo.pdf.util.DocumentEdgeDetector
import kotlinx.coroutines.CoroutineScope
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.cancel
import kotlin.math.min

/**
 * 滤镜类型枚举
 */
enum class FilterType {
    NONE,             // 无滤镜
    SHADOW_REMOVAL,   // 去阴影
    ENHANCE_SHARPEN,  // 增强锐化
    GRAYSCALE,        // 灰度
    BLACK_WHITE,      // 黑白
    INK_SAVING        // 省墨
}

/**
 * 图像预览编辑视图，集成裁剪框和图像编辑功能
 */
class ImagePreViewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    companion object {
        // 常量定义
        private const val DEFAULT_MARGIN = 50f
    }


    // 滤镜相关常量
    private val DEFAULT_BRIGHTNESS = 0
    private val DEFAULT_CONTRAST = 0
    private val DEFAULT_SHARPNESS = 50
    private val DEFAULT_DETAIL = 30

    // 图像相关变量
    private var originalBitmap: Bitmap? = null // 原始图像，用于滤镜和旋转计算
    private var imageBitmap: Bitmap? = null // 当前显示的图像（应用滤镜和旋转后）
    private val imageMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var isProcessing = false

    // 滤镜相关变量
    private val filterProcessor = ImageFilterProcessor()
    private var currentFilter = FilterType.NONE
    private var brightness = DEFAULT_BRIGHTNESS
    private var contrast = DEFAULT_CONTRAST
    private var sharpness = DEFAULT_SHARPNESS
    private var detailStrength = DEFAULT_DETAIL

    // 裁剪选择视图
    private val cropSelectionView by lazy {
        CropSelectionView(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onPointsChanged = { vertices, curvePoints ->
                // 裁剪框坐标变化回调
                Log.d(
                    "ImagePreViewView",
                    "裁剪框坐标变化: vertices=\$vertices, curvePoints=\$curvePoints"
                )
            }
        }
    }

    // 放大镜视图
    private var magnifierView: MagnifierView? = null

    // 协程作用域，与View生命周期绑定
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    // 处理状态监听器
    var processingListener: ProcessingListener? = null

    /**
     * 图像处理状态监听器
     */
    interface ProcessingListener {
        fun onProcessingStart() {}
        fun onProcessingComplete() {}
        fun onSuccess(result: Any? = null) {}
        fun onFailure(e: Exception) {}
    }

    init {
        addView(cropSelectionView)
    }

    /**
     * 设置图像
     */
    fun setImageBitmap(bitmap: Bitmap?) {
        originalBitmap?.recycle()
        originalBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        resetFilterParameters()
        applyCurrentFilter()
        cropSelectionView.setupDefaultPoints()
        requestLayout()
        invalidate()
    }

    /**
     * 设置裁剪框是否可见
     */
    fun setCropViewVisible(visible: Boolean) {
        cropSelectionView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 一键识别文档框
     * 异步检测文档边缘
     */
    fun detectDocumentEdges() {
        if (isProcessing || originalBitmap == null) return
        isProcessing = true
        processingListener?.onProcessingStart()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val bitmap = originalBitmap!!
                // 将Bitmap转换为OpenCV的Mat
                val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                mat.put(0, 0, pixels)

                // 转换为BGR格式
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_RGBA2BGR)

                // 检测边缘
                val points = runCatching {
                    DocumentEdgeDetector.detectDocumentEdges(bgrMat)
                }.getOrNull() ?: listOf(
                    Point(50.0, 50.0),
                    Point(bgrMat.cols() - 50.0, 50.0),
                    Point(bgrMat.cols() - 50.0, bgrMat.rows() - 50.0),
                    Point(50.0, bgrMat.rows() - 50.0)
                )

                // 验证检测结果
                if (points.size >= 8) {
                    // 转换图像坐标到视图坐标
                    val vertices = points.take(4).map {
                        convertImageToViewPoint(
                            CropSelectionView.Point(
                                it.x.toFloat(),
                                it.y.toFloat()
                            )
                        )
                    }
                    val curvePoints = points.drop(4).map {
                        convertImageToViewPoint(
                            CropSelectionView.Point(
                                it.x.toFloat(),
                                it.y.toFloat()
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        cropSelectionView.setVertices(vertices)
                        cropSelectionView.setCurvePoints(curvePoints)
                        processingListener?.onSuccess()
                    }
                } else {
                    Log.e("ImagePreViewView", "文档边缘检测失败，点数量不足")
                }

                // 释放资源
                mat.release()
                bgrMat.release()
            } catch (e: Exception) {
                Log.e("ImagePreViewView", "文档边缘检测异常", e)
                withContext(Dispatchers.Main) {
                    processingListener?.onFailure(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    processingListener?.onProcessingComplete()
                }
            }
        }
    }

    /**
     * 一键裁剪框铺满图像
     */
    fun fitToImage() {
        imageBitmap?.let {
            val margin = DEFAULT_MARGIN
            val vertices = listOf(
                CropSelectionView.Point(margin, margin),
                CropSelectionView.Point(width - margin, margin),
                CropSelectionView.Point(width - margin, height - margin),
                CropSelectionView.Point(margin, height - margin)
            )
            cropSelectionView.setVertices(vertices)
        }
    }

    /**
     * 向左旋转图像
     */
    fun rotateLeft() {
        originalBitmap?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val rotated = rotateBitmap(it, -90)
                originalBitmap?.recycle()
                originalBitmap = rotated
                withContext(Dispatchers.Main) {
                    applyCurrentFilter()
                }
            }
        }
    }

    /**
     * 向右旋转图像
     */
    fun rotateRight() {
        originalBitmap?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val rotated = rotateBitmap(it, 90)
                originalBitmap?.recycle()
                originalBitmap = rotated
                withContext(Dispatchers.Main) {
                    applyCurrentFilter()
                }
            }
        }
    }

    /**
     * 旋转Bitmap图像
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 设置放大镜视图
     */
    fun setMagnifierView(magnifier: MagnifierView) {
        this.magnifierView = magnifier
        cropSelectionView.setMagnifierView(magnifier)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 布局子视图
        cropSelectionView.layout(0, 0, r - l, b - t)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 测量子视图
        cropSelectionView.measure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制图像
        imageBitmap?.let { bitmap ->
            val drawMatrix = Matrix(imageMatrix)
            // 计算图像缩放以适应视图
            val scale = calculateScale(bitmap.width.toFloat(), bitmap.height.toFloat())
            drawMatrix.postScale(scale, scale, width / 2f, height / 2f)
            canvas.drawBitmap(bitmap, drawMatrix, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 取消所有协程
        coroutineScope.cancel()
        // 释放资源
        imageBitmap?.recycle()
        originalBitmap?.recycle()
        imageBitmap = null
        originalBitmap = null
        isProcessing = false
    }

    /**
     * 将图像坐标系的点转换为视图坐标系
     */
    private fun convertImageToViewPoint(imagePoint: CropSelectionView.Point): CropSelectionView.Point {
        originalBitmap?.let { bitmap ->
            val scale = calculateScale(bitmap.width.toFloat(), bitmap.height.toFloat())
            val offsetX = (width - bitmap.width * scale) / 2
            val offsetY = (height - bitmap.height * scale) / 2
            return CropSelectionView.Point(
                imagePoint.x * scale + offsetX,
                imagePoint.y * scale + offsetY
            )
        }
        return imagePoint
    }

    /**
     * 计算图像缩放比例以适应视图
     */
    private fun calculateScale(imageWidth: Float, imageHeight: Float): Float {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        return if (imageWidth == 0f || imageHeight == 0f) {
            1f
        } else {
            min(viewWidth / imageWidth, viewHeight / imageHeight)
        }
    }


    /**
     * 异步应用透视变换提取文档
     */
    fun extractDocument() {
        if (isProcessing || imageBitmap == null) return
        isProcessing = true
        processingListener?.onProcessingStart()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val bitmap = imageBitmap!!
                // 将Bitmap转换为OpenCV的Mat
                val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                mat.put(0, 0, pixels)

                // 获取裁剪框的顶点和曲线点
                val vertices = cropSelectionView.getVertices()
                    .map { Point(it.x.toDouble(), it.y.toDouble()) }
                val curvePoints = cropSelectionView.getCurrentPoints()
                    .map { Point(it.x.toDouble(), it.y.toDouble()) }

                // 应用透视变换
                val resultMat = DocumentEdgeDetector.perspectiveTransform(
                    mat,
                    vertices,
                    curvePoints,
                    bitmap.width,
                    bitmap.height
                )

                // 将Mat转换回Bitmap
                val resultPixels = IntArray(resultMat.width() * resultMat.height())
                resultMat.get(0, 0, resultPixels)
                val resultBitmap = createBitmap(resultMat.width(), resultMat.height())
                resultBitmap.setPixels(
                    resultPixels,
                    0,
                    resultMat.width(),
                    0,
                    0,
                    resultMat.width(),
                    resultMat.height()
                )

                // 释放资源
                mat.release()
                resultMat.release()

                withContext(Dispatchers.Main) {
                    processingListener?.onSuccess(resultBitmap)
                }
            } catch (e: Exception) {
                Log.e("ImagePreViewView", "透视变换提取失败", e)
                withContext(Dispatchers.Main) {
                    processingListener?.onFailure(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    processingListener?.onProcessingComplete()
                }
            }
        }
    }

    /**
     * 获取经过旋转、滤镜后的当前图像
     */
    fun getCurrentImage(): Bitmap? {
        return imageBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 获取原始图像
     */
    fun getOriginalImage(): Bitmap? {
        return originalBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 设置滤镜类型
     */
    fun setFilterType(filterType: FilterType) {
        if (currentFilter != filterType) {
            currentFilter = filterType
            applyCurrentFilter()
        }
    }

    /**
     * 调整亮度
     * @param value 亮度值 (-100~100)
     */
    fun adjustBrightness(value: Int) {
        if (brightness != value) {
            brightness = value.coerceIn(-100, 100)
            applyCurrentFilter()
        }
    }

    /**
     * 调整对比度
     * @param value 对比度值 (-100~100)
     */
    fun adjustContrast(value: Int) {
        if (contrast != value) {
            contrast = value.coerceIn(-100, 100)
            applyCurrentFilter()
        }
    }

    /**
     * 调整文字细节
     * @param value 细节强度 (0~100)
     */
    fun adjustTextDetail(value: Int) {
        if (detailStrength != value) {
            detailStrength = value.coerceIn(0, 100)
            if (currentFilter == FilterType.ENHANCE_SHARPEN) {
                applyCurrentFilter()
            }
        }
    }

    /**
     * 重置滤镜参数
     */
    private fun resetFilterParameters() {
        brightness = DEFAULT_BRIGHTNESS
        contrast = DEFAULT_CONTRAST
        sharpness = DEFAULT_SHARPNESS
        detailStrength = DEFAULT_DETAIL
        currentFilter = FilterType.NONE
    }

    /**
     * 应用当前选中的滤镜
     */
    private fun applyCurrentFilter() {
        if (isProcessing || originalBitmap == null) return
        isProcessing = true
        processingListener?.onProcessingStart()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val resultBitmap = when (currentFilter) {
                    FilterType.NONE -> originalBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    FilterType.SHADOW_REMOVAL -> filterProcessor.applyShadowRemoval(
                        originalBitmap!!,
                        brightness,
                        contrast
                    )

                    FilterType.ENHANCE_SHARPEN -> filterProcessor.applyEnhanceAndSharpen(
                        originalBitmap!!,
                        brightness,
                        contrast,
                        sharpness,
                        detailStrength
                    )

                    FilterType.GRAYSCALE -> filterProcessor.applyGrayscale(
                        originalBitmap!!,
                        brightness,
                        contrast
                    )

                    FilterType.BLACK_WHITE -> filterProcessor.applyBlackWhite(originalBitmap!!)
                    FilterType.INK_SAVING -> filterProcessor.applyInkSaving(originalBitmap!!)
                }

                withContext(Dispatchers.Main) {
                    imageBitmap?.recycle()
                    imageBitmap = resultBitmap
                    invalidate()
                    processingListener?.onSuccess()
                }
            } catch (e: Exception) {
                Log.e("ImagePreViewView", "滤镜应用失败", e)
                withContext(Dispatchers.Main) {
                    processingListener?.onFailure(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    processingListener?.onProcessingComplete()
                }
            }
        }
    }
}
