package com.foxluo.pdf.ui.view

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.AttributeSet
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.impl.UseCaseConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SPUtils
import com.foxluo.pdf.util.DocumentEdgeDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import androidx.core.view.isVisible

// 闪光灯模式枚举
enum class FlashMode { OFF, ON, AUTO, TORCH }

@ExperimentalCamera2Interop
class CameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // 视图组件
    // 初始化子视图
    private val previewView: PreviewView by lazy {
        PreviewView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
    }

    private val cropSelectionView: CropSelectionView by lazy {
        CropSelectionView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = INVISIBLE
        }
    }

    private val gridLinesView: GridLinesView by lazy {
        GridLinesView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = GONE
        }
    }

    // 相机相关变量
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var lifecycleOwner: LifecycleOwner? = null

    // 配置参数
    private var _flashMode: FlashMode = FlashMode.OFF
    private var isFlashAvailable: Boolean = false
    private var currentResolution: Size? = null
    private var _supportedResolutions: MutableList<Size> = mutableListOf()

    // 文档检测相关
    private var documentDetectedCallback: (() -> Unit)? = null
    private var stabilityCheckDuration: Long = 2000
    private var detectionStartTime: Long = 0
    private var isDocumentStable = false
    private var lastDetectedVertices: List<Point>? = null
    private val detectionThreshold = 5.0

    /**
     * 更新裁剪选择视图的文档框
     */
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // 自动拍照功能开关
    var isAutoCaptureEnabled: Boolean = false
        set(value) {
            field = value
        }

    // 输出照片方向
    var imageOrientation: ImageOrientation = ImageOrientation.AUTO
        set(value) {
            field = value
            startCamera()
        }

    // 显示网格线开关
    var isGridLinesEnabled: Boolean = false
        set(value) {
            field = value
            gridLinesView.visibility = if (value) VISIBLE else GONE
        }

    // 拍照提示音开关
    var isCaptureSoundEnabled: Boolean = false

    // 输出照片方向枚举
    enum class ImageOrientation { AUTO, LANDSCAPE, PORTRAIT }

    init {
        // 添加子视图到FrameLayout
        addView(previewView)
        addView(cropSelectionView)
        addView(gridLinesView)
    }

    /**
     * 设置文档稳定检测时间（毫秒）
     */
    fun setStabilityCheckDuration(duration: Long) {
        this.stabilityCheckDuration = duration
    }

    /**
     * 设置文档检测稳定后的回调
     */
    fun setDocumentDetectedCallback(callback: () -> Unit) {
        this.documentDetectedCallback = callback
    }

    /**
     * 设置生命周期所有者
     */
    fun setLifecycleOwner(owner: LifecycleOwner) {
        this.lifecycleOwner = owner
        startCamera()
    }

    /**
     * 启动相机预览和分析
     */
    private fun startCamera() {
        if (lifecycleOwner == null) {
            LogUtils.e("CameraPreviewView", "必须先调用setLifecycleOwner设置生命周期所有者")
            return
        }

        if (!checkCameraPermission()) {
            LogUtils.e("CameraPreviewView", "相机权限未授予")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (exc: Exception) {
                LogUtils.e("CameraPreviewView", "相机初始化失败: ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 检查相机权限
     */
    private fun checkCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 设置分辨率
     */
    fun setResolution(size: Size): Boolean {
        if (_supportedResolutions.contains(size)) {
            currentResolution = size
            // 保存分辨率到本地
            SPUtils.getInstance().put("ResolutionWidth", size.width)
            SPUtils.getInstance().put("ResolutionHeight", size.height)
            startCamera()
            return true
        }
        return false
    }

    /**
     * 获取当前分辨率
     * 若未设置使用获取上次设置保存的分辨率，默认使用1080p分辨率
     */
    fun getCurrentResolution(): Size = currentResolution ?: Size(
        SPUtils.getInstance().getInt("ResolutionWidth", 1920),
        SPUtils.getInstance().getInt("ResolutionHeight", 1080)
    )

    /**
     * 获取支持的分辨率列表
     * 初始化完成前只提供当前/默认分辨率可选
     */
    fun getSupportedResolutions(): List<Size> =
        _supportedResolutions.ifEmpty { listOf(getCurrentResolution()) }

    /**
     * 绑定相机用例
     */
    private fun bindCameraUseCases() {
        val resolution = getCurrentResolution()

        // 配置预览用例
        preview = Preview.Builder()
            .setTargetResolution(resolution)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        // 配置图像分析用例
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    Executors.newSingleThreadExecutor().also { cameraExecutor = it },
                    DocumentAnalyzer { processDetectionResult(it) })
            }
        // 选择后置摄像头
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            // 预览前先解绑
            cameraProvider?.unbindAll()
            // 绑定用例到生命周期
            cameraProvider?.bindToLifecycle(
                lifecycleOwner ?: return,
                cameraSelector,
                preview,
                imageAnalyzer
            )?.let { camera ->
                // 初始化相机配置
                isFlashAvailable = camera.cameraInfo.hasFlashUnit()
                Camera2CameraInfo.from(camera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)
                    ?.toList()
                    ?.let {
                        _supportedResolutions.clear()
                        _supportedResolutions.addAll(it)
                    }
            }
        } catch (exc: Exception) {
            LogUtils.e("CameraPreviewView", "相机绑定失败: ${exc.message}", exc)
        }
    }

    /**
     * 设置闪光灯模式
     */
    fun setFlashMode(mode: FlashMode): Boolean {
        if (!isFlashAvailable && mode != FlashMode.OFF) return false
        if (_flashMode != mode) {
            _flashMode = mode
            startCamera()
        }
        return true
    }

    /**
     * 获取当前闪光灯模式
     */
    fun getFlashMode(): FlashMode = _flashMode

    /**
     * 根据闪光灯模式获取自动曝光模式
     */
    private fun getAeModeForFlash(): Int {
        return when (_flashMode) {
            FlashMode.OFF -> CaptureRequest.CONTROL_AE_MODE_ON
            FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            FlashMode.TORCH -> CaptureRequest.CONTROL_AE_MODE_OFF
        }
    }

    /**
     * 获取闪光灯捕获模式
     */
    private fun getFlashCaptureMode(): Int {
        return when (_flashMode) {
            FlashMode.TORCH -> CaptureRequest.FLASH_MODE_TORCH
            FlashMode.ON, FlashMode.AUTO -> CaptureRequest.FLASH_MODE_SINGLE
            else -> CaptureRequest.FLASH_MODE_OFF
        }
    }

    /**
     * 配置Camera2Interop参数
     */
    @SuppressLint("RestrictedApi")
    private fun configureCamera2Interop(builder: UseCaseConfig.Builder<*, *, *>) {
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, getAeModeForFlash())
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            .apply {
                val flashMode = getFlashCaptureMode()
                if (flashMode == ImageCapture.FLASH_MODE_ON) {
                    setCaptureRequestOption(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_TORCH
                    )
                } else if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
                    setCaptureRequestOption(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_SINGLE
                    )
                }
            }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCamera()
    }

    /**
     * 停止相机和分析
     */
    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        try {
            cameraExecutor?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            e.printStackTrace()
            Thread.currentThread().interrupt()
        }
        cameraExecutor = null
        cameraProvider = null
    }

    /**
     * 处理文档检测结果
     */
    private fun processDetectionResult(points: List<Point>?) {
        // 如果自动识别功能未启用，直接返回
        if (!isAutoCaptureEnabled) return
        CoroutineScope(Dispatchers.Main).launch {
            if (points == null) {
                cropSelectionView.visibility = INVISIBLE
            } else {
                cropSelectionView.visibility = VISIBLE
            }
        }
        if (points != null && points.size >= 4) {
            // 有检测结果，更新裁剪视图
            updateCropSelectionView(points)

            // 检查是否稳定
            if (isDocumentStable(points)) {
                if (detectionStartTime == 0L) {
                    detectionStartTime = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - detectionStartTime >= stabilityCheckDuration) {
                    if (!isDocumentStable) {
                        isDocumentStable = true
                        documentDetectedCallback?.invoke()
                        // 播放拍照声音
                        if (isCaptureSoundEnabled) {
                            playCaptureSound()
                        }
                    }
                }
            } else {
                // 文档不稳定，重置计时器
                detectionStartTime = 0
                isDocumentStable = false
            }
            lastDetectedVertices = points.take(4)
        } else {
            // 未检测到文档
            detectionStartTime = 0
            isDocumentStable = false
            lastDetectedVertices = null
        }
    }

    /**
     * 检查文档是否稳定（顶点位置变化在阈值内）
     */
    private fun isDocumentStable(newVertices: List<Point>): Boolean {
        val oldVertices = lastDetectedVertices ?: return true
        if (newVertices.size != oldVertices.size) return false

        for (i in newVertices.indices) {
            val dx = newVertices[i].x - oldVertices[i].x
            val dy = newVertices[i].y - oldVertices[i].y
            if (dx * dx + dy * dy > detectionThreshold * detectionThreshold) {
                return false
            }
        }
        return true
    }

    /**
     *
     */
    private fun updateCropSelectionView(points: List<Point>) {
        // 根据图片方向调整坐标
        if (points.size >= 8) {
            // 计算缩放因子
            val scaleX = width.toFloat() / imageWidth
            val scaleY = height.toFloat() / imageHeight
            val scale = min(scaleX, scaleY)

            // 计算偏移量（居中显示）
            val offsetX = (width.toFloat() - imageWidth * scale) / 2
            val offsetY = (height.toFloat() - imageHeight * scale) / 2

            // 缩放并偏移坐标点
            val vertices = points.subList(0, 4).map {
                CropSelectionView.Point(
                    it.x.toFloat() * scale + offsetX,
                    it.y.toFloat() * scale + offsetY
                )
            }
            val curvePoints = points.subList(4, 8).map {
                CropSelectionView.Point(
                    it.x.toFloat() * scale + offsetX,
                    it.y.toFloat() * scale + offsetY
                )
            }
            cropSelectionView.setVertices(vertices)
            cropSelectionView.setCurvePoints(curvePoints)
        }
    }

    /**
     * 播放拍照提示音
     */
    private fun playCaptureSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mediaPlayer = MediaPlayer.create(context, uri)
            if (mediaPlayer != null) {
                mediaPlayer.start()
                mediaPlayer.setOnCompletionListener { it.release() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 处理分析图像类
     * 分析图像中文档框，并将文档点回调绘制到裁剪view
     */
    private inner class DocumentAnalyzer(private val onDetected: (List<Point>?) -> Unit) :
        ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(image: ImageProxy) {
            imageWidth = image.width
            imageHeight = image.height
            val mediaImage = image.image ?: run {
                image.close()
                return
            }

            // 将YUV图像转换为Bitmap
            // 将YUV_420_888图像转换为Bitmap
            val yBuffer = mediaImage.planes[0].buffer
            val uBuffer = mediaImage.planes[1].buffer
            val vBuffer = mediaImage.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage =
                YuvImage(nv21, ImageFormat.NV21, mediaImage.width, mediaImage.height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, mediaImage.width, mediaImage.height),
                100,
                outputStream
            )
            val bitmap =
                BitmapFactory.decodeByteArray(outputStream.toByteArray(), 0, outputStream.size())

            // 转换为OpenCV Mat
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // 检测文档边缘
            val points = runCatching {
                DocumentEdgeDetector.detectDocumentEdges(mat)
            }.getOrNull()

            // 回调检测结果
            onDetected(points)

            // 释放资源
            mat.release()
            bitmap.recycle()
            image.close()
        }
    }

    /**
     * 网格线绘制视图
     */
    private inner class GridLinesView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.argb(100, 255, 255, 255)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawGridLines(canvas)
        }

        private fun drawGridLines(canvas: Canvas) {
            val horizontalLines = 2
            val verticalLines = 2
            val cellWidth = width.toFloat() / (verticalLines + 1)
            val cellHeight = height.toFloat() / (horizontalLines + 1)

            // 绘制水平线
            for (i in 1..horizontalLines) {
                val y = cellHeight * i
                canvas.drawLine(0f, y, width.toFloat(), y, paint)
            }

            // 绘制垂直线
            for (i in 1..verticalLines) {
                val x = cellWidth * i
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
        }
    }
}

