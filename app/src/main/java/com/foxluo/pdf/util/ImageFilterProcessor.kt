package com.foxluo.pdf.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ImageFilterProcessor {
    /**
     * 应用去阴影滤镜
     * @param bitmap 原始图像
     * @param brightness 亮度调节 (-100~100)
     * @param contrast 对比度调节 (-100~100)
     * @return 处理后的图像
     */
    fun applyShadowRemoval(bitmap: Bitmap, brightness: Int = 0, contrast: Int = 0): Bitmap {
        return processFilter(bitmap) { srcMat ->
            // 转换为灰度图
            val grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // 顶帽变换去除阴影
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            val topHat = Mat()
            Imgproc.morphologyEx(grayMat, topHat, Imgproc.MORPH_TOPHAT, kernel)

            // 合并结果
            val shadowRemoved = Mat()
            Core.addWeighted(grayMat, 1.0, topHat, -1.0, 0.0, shadowRemoved)

            // 转换回BGR
            val resultMat = Mat()
            Imgproc.cvtColor(shadowRemoved, resultMat, Imgproc.COLOR_GRAY2BGR)

            // 应用亮度对比度调节
            adjustBrightness(resultMat, brightness)
            adjustContrast(resultMat, contrast)
            resultMat
        }
    }

    /**
     * 应用增强并锐化滤镜
     * @param bitmap 原始图像
     * @param brightness 亮度调节 (-100~100)
     * @param contrast 对比度调节 (-100~100)
     * @param sharpness 锐化程度 (0~100)
     * @param detailStrength 细节增强强度 (0~100)
     * @return 处理后的图像
     */
    fun applyEnhanceAndSharpen(bitmap: Bitmap, brightness: Int = 0, contrast: Int = 0, sharpness: Int = 50, detailStrength: Int = 30): Bitmap {
        return processFilter(bitmap) { srcMat ->
            // 先调整亮度对比度
            val adjustedMat = srcMat.clone()
            adjustBrightness(adjustedMat, brightness)
            adjustContrast(adjustedMat, contrast)

            // 锐化处理
            val sharpenedMat = Mat()
            val sharpnessFactor = sharpness / 50.0
            val kernel = Mat(3, 3, CvType.CV_32F).apply {
                put(0, 0, 0.0, -sharpnessFactor, 0.0,
                    -sharpnessFactor, 1.0 + 4 * sharpnessFactor, -sharpnessFactor,
                    0.0, -sharpnessFactor, 0.0)
            }
            Imgproc.filter2D(adjustedMat, sharpenedMat, -1, kernel)
            // 增强文字细节
            enhanceTextDetails(sharpenedMat, detailStrength)
            sharpenedMat
        }
    }

    /**
     * 应用灰度滤镜
     * @param bitmap 原始图像
     * @param brightness 亮度调节 (-100~100)
     * @param contrast 对比度调节 (-100~100)
     * @return 处理后的图像
     */
    fun applyGrayscale(bitmap: Bitmap, brightness: Int = 0, contrast: Int = 0): Bitmap {
        return processFilter(bitmap) { srcMat ->
            val grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_GRAY2BGR)
            adjustBrightness(grayMat, brightness)
            adjustContrast(grayMat, contrast)
            grayMat
        }
    }

    /**
     * 应用黑白滤镜
     * @param bitmap 原始图像
     * @param threshold 阈值调节 (0~255)
     * @return 处理后的图像
     */
    fun applyBlackWhite(bitmap: Bitmap, threshold: Int = 127): Bitmap {
        return processFilter(bitmap) { srcMat ->
            val grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)
            val binaryMat = Mat()
            Imgproc.threshold(grayMat, binaryMat, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Imgproc.cvtColor(binaryMat, binaryMat, Imgproc.COLOR_GRAY2BGR)
            binaryMat
        }
    }

    /**
     * 应用省墨滤镜
     * @param bitmap 原始图像
     * @param intensity 文字减细强度 (0~100)，值越高文字越细
     * @param threshold 二值化阈值 (0~255)
     * @return 处理后的图像
     */
    fun applyInkSaving(bitmap: Bitmap, intensity: Int = 30, threshold: Int = 127): Bitmap {
        return processFilter(bitmap) { srcMat ->
            // 转换为灰度图
            val grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // 二值化处理 (文字为白色，背景为黑色)
            val binaryMat = Mat()
            Imgproc.threshold(grayMat, binaryMat, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)

            // 根据强度计算腐蚀核大小 (1~5)
            val kernelSize = (intensity.coerceIn(0, 100) / 20) + 1
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelSize.toDouble(), kernelSize.toDouble()))

            // 应用腐蚀操作减少文字粗细
            val erodedMat = Mat()
            Imgproc.erode(binaryMat, erodedMat, kernel)

            // 反转回正常颜色并转换为BGR
            Core.bitwise_not(erodedMat, erodedMat)
            Imgproc.cvtColor(erodedMat, erodedMat, Imgproc.COLOR_GRAY2BGR)
            erodedMat
        }
    }

    /**
     * 通用滤镜处理流程
     */
    private fun processFilter(bitmap: Bitmap, filterOperation: (Mat) -> Mat): Bitmap {
        if (bitmap.isRecycled) {
            throw IllegalArgumentException("Bitmap is recycled")
        }

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        return try {
            val resultMat = filterOperation(srcMat)
            val resultBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resultMat, resultBitmap)
            resultBitmap
        } finally {
            srcMat.release()
        }
    }

    /**
     * 调整亮度
     * @param mat 输入图像矩阵
     * @param brightness 亮度值 (-100~100)
     */
    private fun adjustBrightness(mat: Mat, brightness: Int) {
        val beta = brightness.toDouble()
        mat.convertTo(mat, -1, 1.0, beta)
    }

    /**
     * 调整对比度
     * @param mat 输入图像矩阵
     * @param contrast 对比度值 (-100~100)
     */
    private fun adjustContrast(mat: Mat, contrast: Int) {
        val alpha = when {
            contrast > 0 -> 1.0 + contrast / 100.0
            contrast < 0 -> 1.0 + contrast / 200.0
            else -> 1.0
        }
        mat.convertTo(mat, -1, alpha, 0.0)
    }

    /**
     * 增强文字细节
     * @param mat 输入图像矩阵
     * @param strength 细节强度 (0~100)
     */
    private fun enhanceTextDetails(mat: Mat, strength: Int) {
        if (strength <= 0) return

        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

        // 使用拉普拉斯算子增强边缘
        val laplacian = Mat()
        Imgproc.Laplacian(grayMat, laplacian, CvType.CV_64F)
        Core.convertScaleAbs(laplacian, laplacian)

        // 归一化处理
        Core.normalize(laplacian, laplacian, 0.0, 1.0, Core.NORM_MINMAX, CvType.CV_32F)

        // 转换回BGR并叠加到原图
        val detailMat = Mat()
        Imgproc.cvtColor(laplacian, detailMat, Imgproc.COLOR_GRAY2BGR)
        val weight = strength / 200.0
        Core.addWeighted(mat, 1.0, detailMat, weight, 0.0, mat)

        grayMat.release()
        laplacian.release()
        detailMat.release()
    }
}