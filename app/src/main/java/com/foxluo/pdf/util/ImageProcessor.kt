package com.foxluo.pdf.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ImageProcessor {
    /**
     * 旋转Bitmap图像
     * @param bitmap 原始图像
     * @param angle 旋转角度（度），正值为顺时针方向
     * @return 旋转后的Bitmap
     */
    fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
        if (bitmap.isRecycled) {
            throw IllegalArgumentException("Bitmap is recycled")
        }
        if ((angle % 360).toInt() == 0) {
            return bitmap.copy(bitmap.config?:Bitmap.Config.ARGB_8888, false) // 无需旋转，返回副本
        }

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val center = Point(srcMat.cols() / 2.0, srcMat.rows() / 2.0)
        val rotationMatrix = Imgproc.getRotationMatrix2D(center, angle.toDouble(), 1.0)

        // 计算旋转后的图像大小
        val rotatedSize = calculateRotatedSize(srcMat.size(), angle)
        val rotatedMat = Mat()
        Imgproc.warpAffine(srcMat, rotatedMat, rotationMatrix, rotatedSize)

        val rotatedBitmap = createBitmap(rotatedMat.cols(), rotatedMat.rows())
        Utils.matToBitmap(rotatedMat, rotatedBitmap)

        srcMat.release()
        rotatedMat.release()
        return rotatedBitmap
    }

    /**
     * 计算旋转后的图像大小
     */
    private fun calculateRotatedSize(originalSize: Size, angle: Float): Size {
        val radians = Math.toRadians(angle.toDouble())
        val cos = abs(cos(radians))
        val sin = abs(sin(radians))
        return Size(
            originalSize.width * cos + originalSize.height * sin,
            originalSize.width * sin + originalSize.height * cos
        )
    }
}