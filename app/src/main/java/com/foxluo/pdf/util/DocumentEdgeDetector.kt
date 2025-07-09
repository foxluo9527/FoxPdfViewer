package com.foxluo.pdf.util

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.util.Log
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 文档边缘检测器，用于提取图像中文档的边缘坐标
 * 支持检测平铺和非平铺文档，返回顶点和弯曲点坐标
 *
 * @property TAG 日志标签
 */
object DocumentEdgeDetector {
    private const val TAG = "DocumentEdgeDetector"

    /**
     * 检测文档边缘并返回坐标点
     * @param srcMat 输入图像Mat，必须为非空且有效的BGR图像
     * @return 包含8个坐标点的列表，前4个为顶点，后4个为弯曲点
     * @throws IllegalArgumentException 如果输入Mat为空或无效
     */
    fun detectDocumentEdges(srcMat: Mat): List<Point>? {
        require(!srcMat.empty()) { "输入图像Mat不能为空" }
//        require(srcMat.type() == CvType.CV_8UC3) { "输入图像必须是BGR格式的8位3通道图像" }
        // 1. 图像预处理
        val processedMat = preprocessImage(srcMat)
        // 2. 边缘检测
        val edgesMat = detectEdges(processedMat)
        // 3. 轮廓检测与顶点提取
        val vertices = try {
            detectContours(edgesMat)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "轮廓检测失败: ${e.message}")
            return null
        }
        Log.d(TAG, "检测到四边形顶点: ${vertices.size}个")

        // 5. 计算弯曲点
        val curvePoints = calculateCurvePoints(srcMat, vertices)

        // 6. 返回合并后的坐标点（4个顶点 + 4个弯曲点）
        return vertices + curvePoints
    }

    /**
     * 图像预处理：灰度转换、高斯模糊
     */
    private fun preprocessImage(srcMat: Mat): Mat {
        // 调整图像大小以减少内存占用
        val resizedMat = Mat()
        val maxDimension = 500.0
        val scale = maxDimension / max(srcMat.width(), srcMat.height())
        if (scale < 1.0) {
            Imgproc.resize(srcMat, resizedMat, Size(0.0, 0.0), scale, scale, Imgproc.INTER_AREA)
        } else {
            srcMat.copyTo(resizedMat)
        }

        val grayMat = Mat()
        Imgproc.cvtColor(resizedMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        resizedMat.release() // 释放调整大小后的图像

        // 高斯模糊去除干扰 - 使用较小的核并降低 sigma 值
        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(3.0, 3.0), 1.0)
        grayMat.release() // 释放灰度图像

        // 添加Otsu阈值处理增强对比度
        val thresholdMat = Mat()
        Imgproc.threshold(blurredMat, thresholdMat, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        blurredMat.release() // 释放模糊图像

        return thresholdMat
    }

    /**
     * 边缘检测：应用Canny边缘检测并进行膨胀操作连接边缘
     */
    private fun detectEdges(processedMat: Mat): Mat {
        // Canny边缘检测
        val edgesMat = Mat()
        // 降低阈值以检测更多边缘
        Imgproc.Canny(processedMat, edgesMat, 60.0, 240.0, 3)

        // 膨胀操作，连接边缘
        // 增强膨胀操作以连接更多边缘
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edgesMat, edgesMat, kernel, Point(-1.0, -1.0), 3, 1, Scalar(1.0))
        kernel.release()

        return edgesMat
    }

    /**
     * 轮廓检测：寻找最大轮廓并拟合四边形顶点
     */
    private fun detectContours(edgesMat: Mat): List<Point> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()

        // 寻找轮廓
        Imgproc.findContours(
            edgesMat,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        if (contours.isEmpty()) {
            throw IllegalArgumentException("未检测到任何轮廓")
        }

        // 查找前5个最大轮廓并尝试拟合四边形
        val imageArea = edgesMat.width() * edgesMat.height()
        val contourAreas = contours.map { Imgproc.contourArea(it) to it }
            .filter { (area, _) -> area > imageArea * 0.01 && area < imageArea * 0.99 }
            .sortedByDescending { (area, _) -> area }
            .take(5)

        var maxContour: MatOfPoint? = null
        var bestApprox: MatOfPoint2f? = null
        for ((_, contour) in contourAreas) {
            val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            // 进一步放宽轮廓近似条件
            // 调整近似系数，平衡细节与稳定性
            val epsilon = 0.05 * perimeter
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, epsilon, true)
            if (approx.toArray().size >= 4) {
                maxContour = contour
                bestApprox = MatOfPoint2f(approx.clone())
                approx.release()
                break
            }
            approx.release()
        }

        maxContour ?: throw IllegalArgumentException("无法找到有效轮廓")
        bestApprox ?: throw IllegalArgumentException("无法拟合轮廓")

        val vertices = bestApprox.toArray().toList()
        bestApprox.release()

        // 如果顶点数不是4，尝试强制拟合为四边形
        val adjustedVertices = if (vertices.size != 4) {
            Log.w(TAG, "轮廓顶点数不是4，尝试强制拟合: ${vertices.size}")
            forceFitQuadrilateral(vertices)
        } else {
            vertices
        }

        // 释放资源
        hierarchy.release()
        maxContour.release()

        // 检查是否为四边形并验证形状合理性
        if (adjustedVertices.size != 4) {
            throw IllegalArgumentException("检测到的轮廓不是四边形，顶点数量: ${adjustedVertices.size}")
        }

        // 验证四边形的宽高比（避免过窄或过宽的形状）
        val (tl, tr, br, bl) = adjustedVertices
        val width = max(distance(tl, tr), distance(bl, br))
        val height = max(distance(tl, bl), distance(tr, br))
        val aspectRatio = max(width/height, height/width)
        // 放宽宽高比限制
        if (aspectRatio > 5.0) {
            throw IllegalArgumentException("检测到的四边形宽高比过大: $aspectRatio")
        }

        // 优化顶点排序：明确区分四个角点
        return sortVertices(vertices).let { sorted ->
            // 按x+y最小（左上）、x-y最大（右上）、x+y最大（右下）、x-y最小（左下）排序
            val topLeft = sorted.minByOrNull { it.x + it.y }!!
            val bottomRight = sorted.maxByOrNull { it.x + it.y }!!
            val topRight = sorted.maxByOrNull { it.x - it.y }!!
            val bottomLeft = sorted.minByOrNull { it.x - it.y }!!
            listOf(topLeft, topRight, bottomRight, bottomLeft)
        }
    }

    /**
     * 对四边形顶点进行顺时针排序
     */
    private fun sortVertices(vertices: List<Point>): List<Point> {
        // 计算中心点
        val center = Point(vertices.sumOf { it.x } / 4, vertices.sumOf { it.y } / 4)

        // 按极角排序
        return vertices.sortedBy { point ->
            val angle = Math.atan2(point.y - center.y, point.x - center.x) * 180 / Math.PI
            if (angle < 0) angle + 360 else angle
        }
    }

    /**
     * 计算弯曲点坐标
     * 如果是平铺文档，弯曲点为顶点连线的中点
     * 如果是非平铺文档，检测实际弯曲点
     */
    private fun calculateCurvePoints(srcMat: Mat, vertices: List<Point>): List<Point> {
        if (vertices.size != 4) {
            throw IllegalArgumentException("顶点和弯曲点数量必须各为4")
        }

        val (topLeft, topRight, bottomRight, bottomLeft) = vertices
        val curvePoints = mutableListOf<Point>()

        // 检查文档是否平铺（简单判断：各边是否为直线）
        val isFlat = isDocumentFlat(srcMat, vertices)

        // 计算四条边的弯曲点
        curvePoints.add(calculateEdgeCurvePoint(srcMat, topLeft, topRight, isFlat)) // 上边缘
        curvePoints.add(calculateEdgeCurvePoint(srcMat, topRight, bottomRight, isFlat)) // 右边缘
        curvePoints.add(calculateEdgeCurvePoint(srcMat, bottomRight, bottomLeft, isFlat)) // 下边缘
        curvePoints.add(calculateEdgeCurvePoint(srcMat, bottomLeft, topLeft, isFlat)) // 左边缘

        return curvePoints
    }

    /**
     * 判断文档是否平铺
     */
    /**
     * 强制将轮廓顶点拟合为四边形
     */
    private fun forceFitQuadrilateral(vertices: List<Point>): List<Point> {
        // 使用最小面积矩形拟合，支持旋转场景
        val points = MatOfPoint2f(*vertices.map { Point(it.x, it.y) }.toTypedArray())
        val rect = Imgproc.minAreaRect(points)
        val box = MatOfPoint2f()
        Imgproc.boxPoints(rect, box)
        points.release()
        val boxPoints = box.toArray().toList()
        box.release()
        return sortVertices(boxPoints)
    }

    /**
     * 判断文档是否平铺
     * 通过检查顶点形成的四边形是否近似矩形来判断
     */
    private fun isDocumentFlat(srcMat: Mat, vertices: List<Point>): Boolean {
        if (vertices.size != 4) return true

        val (topLeft, topRight, bottomRight, bottomLeft) = vertices

        // 计算各边长度
        val topLength = distance(topLeft, topRight)
        val bottomLength = distance(bottomLeft, bottomRight)
        val leftLength = distance(topLeft, bottomLeft)
        val rightLength = distance(topRight, bottomRight)

        // 计算长宽比差异
        val lengthRatio = max(topLength, bottomLength) / min(topLength, bottomLength)
        val widthRatio = max(leftLength, rightLength) / min(leftLength, rightLength)

        // 如果长宽比接近且差异较小，则视为平铺文档
        val isFlat = lengthRatio < 1.2 && widthRatio < 1.2
        Log.d(TAG, "文档平铺检测结果: \$isFlat")
        return isFlat
    }

    /**
     * 计算两点之间的距离
     */
    private fun distance(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 计算单条边缘的弯曲点
     */
    private fun calculateEdgeCurvePoint(
        srcMat: Mat, start: Point, end: Point, isFlat: Boolean
    ): Point {
        return if (isFlat) {
            // 平铺文档，返回中点
            Point((start.x + end.x) / 2, (start.y + end.y) / 2)
        } else {
            // 非平铺文档，检测实际弯曲点
            detectActualCurvePoint(srcMat, start, end)
        }
    }

    /**
     * 检测实际弯曲点（非平铺文档）
     */
    /**
     * 检测实际弯曲点（非平铺文档）
     * 使用边缘点到直线的最大距离来确定弯曲点
     */
    private fun detectActualCurvePoint(srcMat: Mat, start: Point, end: Point): Point {
        // 创建感兴趣区域
        val rect = Rect(
            min(start.x, end.x).toInt(),
            min(start.y, end.y).toInt(),
            abs(end.x - start.x).toInt(),
            abs(end.y - start.y).toInt()
        ).apply {
            // 扩展区域以确保包含完整边缘
            x = max(0, x - 10)
            y = max(0, y - 10)
            width = min(srcMat.cols() - x, width + 20)
            height = min(srcMat.rows() - y, height + 20)
        }

        val roiMat = Mat(srcMat, rect)
        val edgesMat = Mat()
        Imgproc.Canny(roiMat, edgesMat, 50.0, 150.0)

        var maxDist = 0.0
        var curvePoint = Point((start.x + end.x) / 2, (start.y + end.y) / 2)

        // 遍历边缘点找到离直线最远的点作为弯曲点
        for (y in 0 until edgesMat.rows()) {
            for (x in 0 until edgesMat.cols()) {
                val pixel = edgesMat.get(y, x)
                if (pixel[0] > 0) {
                    val imagePoint = Point(x + rect.x.toDouble(), y + rect.y.toDouble())
                    val dist = distanceToLine(imagePoint, start, end)
                    if (dist > maxDist) {
                        maxDist = dist
                        curvePoint = imagePoint
                    }
                }
            }
        }

        roiMat.release()
        edgesMat.release()

        // 如果最大距离小于阈值，则返回中点
        return if (maxDist < 5.0) Point(
            (start.x + end.x) / 2, (start.y + end.y) / 2
        ) else curvePoint
    }

    /**
     * 计算点到直线的距离
     */
    private fun distanceToLine(point: Point, lineStart: Point, lineEnd: Point): Double {
        val numerator =
            abs((lineEnd.y - lineStart.y) * point.x - (lineEnd.x - lineStart.x) * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x)
        val denominator = distance(lineStart, lineEnd)
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    /**
     * 透视变换方法
     * @param srcMat 原始图像
     * @param vertices 四个顶点坐标
     * @param curvePoints 四个弯曲点坐标
     * @param width 输出宽度
     * @param height 输出高度
     * @return 变换后的图像
     */
    fun perspectiveTransform(
        srcMat: Mat,
        vertices: List<Point>,
        curvePoints: List<Point>,
        width: Int,
        height: Int
    ): Mat {
        if (vertices.size != 4) {
            throw IllegalArgumentException("顶点数量必须为4")
        }

        // 结合顶点和弯曲点优化源点坐标
        val optimizedVertices = vertices.zip(curvePoints) { vertex, curve ->
            Point(
                (vertex.x * 0.7 + curve.x * 0.3),
                (vertex.y * 0.7 + curve.y * 0.3)
            )
        }
        val sortedVertices = sortVertices(optimizedVertices)
        val (tl, tr, br, bl) = sortedVertices

        // 定义源点和目标点（严格对应左上、右上、右下、左下）
        val srcPoints = MatOfPoint2f(tl, tr, br, bl)
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()),
            Point(0.0, height.toDouble())
        )

        // 计算透视变换矩阵
        val perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)

        // 应用透视变换，使用双线性插值提高图像质量
        val dstMat = Mat()
        Imgproc.warpPerspective(
            srcMat, dstMat, perspectiveMatrix, Size(width.toDouble(), height.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0)
        )

        // 释放临时Mat资源
        srcPoints.release()
        dstPoints.release()
        perspectiveMatrix.release()

        return dstMat
    }

}

