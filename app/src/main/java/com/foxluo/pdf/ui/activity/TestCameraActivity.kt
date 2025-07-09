package com.foxluo.pdf.ui.activity

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import com.blankj.utilcode.util.ToastUtils
import com.foxluo.pdf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.CameraActivity
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Arrays
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TestCameraActivity : CameraActivity(), CvCameraViewListener2 {
    /**
     * CV相机
     */
    private var mCVCamera: CameraBridgeViewBase? = null

    private var image_view: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_test)
        initView()
    }

    private fun initView() {
        image_view = findViewById<ImageView>(R.id.image_view)
        // 初始化CV相机
        mCVCamera = findViewById<CameraBridgeViewBase?>(R.id.camera_view)
        mCVCamera!!.setVisibility(CameraBridgeViewBase.VISIBLE)
        // 设置相机监听
        mCVCamera!!.setCvCameraViewListener(this)
    }


    override fun getCameraViewList(): MutableList<out CameraBridgeViewBase?> {
        return Arrays.asList<CameraBridgeViewBase?>(mCVCamera)
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
    }

    override fun onCameraViewStopped() {
    }

    override fun onCameraFrame(inputFrame: CvCameraViewFrame): Mat? {
        // 获取相机中的图像
        val rgba = inputFrame.rgba()
        Core.rotate(rgba, rgba, Core.ROTATE_90_CLOCKWISE)
        val bitmap = createBitmap(rgba.cols(), rgba.rows())
        Utils.matToBitmap(rgba, bitmap)
        val mat = Mat()
        val bmp = location(bitmap) ?: return null
        Utils.bitmapToMat(bmp, mat)
        return mat
    }

    private fun location(bmp: Bitmap): Bitmap? {

        val originMat = Mat()
        Utils.bitmapToMat(bmp, originMat)
        val resultG = Mat()
        val result = Mat()
        Imgproc.GaussianBlur(originMat, resultG, Size(3.0, 3.0), 0.0)
        Imgproc.Canny(resultG, result, 100.0, 220.0, 3)
        // 膨胀，连接边缘
        Imgproc.dilate(result, result, Mat(), Point(-1.0, -1.0), 4, 1, Scalar(1.0))

        //        Bitmap Bmp = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888);
//        Utils.matToBitmap(result,Bmp);
        val contours: MutableList<MatOfPoint> = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            result,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        //有了轮廓之后，为了方便我们先将轮廓给画出来，这里的resultMat其实就是srcMat，为了区分用了Mat resultMat = srcMat.clone();，
        // 下面代码的意思是，把contours轮廓列表中的所有轮廓(-1表示所有轮廓)在，resultMat上画用黑色(new Scalar(0, 0, 0))粗细为10的线条画出来。
        if (contours.isEmpty()) {
            return null
        }
        val resultMat = resultG.clone()

        var arcLength = 0.0
        var index = 0
        for (i in contours.indices) {
            val source = MatOfPoint2f()
            source.fromList(contours.get(i).toList())
            if (Imgproc.arcLength(source, true) > arcLength) {
                arcLength = Imgproc.arcLength(source, true)
                index = i
            }
        }
        val matOfPoint = contours.get(index)
        val tempMat = MatOfPoint2f()
        Imgproc.approxPolyDP(
            MatOfPoint2f(*matOfPoint.toArray()),
            tempMat,
            Imgproc.arcLength(MatOfPoint2f(*matOfPoint.toArray()), true) * 0.04,
            true
        )
        val points = tempMat.toArray()
        if (points.size != 4) {
            return null
        }
        val matOfPoints: MutableList<MatOfPoint?> = ArrayList<MatOfPoint?>()
        matOfPoints.add(MatOfPoint(*tempMat.toArray()))

        Imgproc.drawContours(resultMat, matOfPoints, -1, Scalar(0.0, 0.0, 255.0), 4)

        val resultBmp =
            createBitmap(resultMat.cols(), resultMat.rows())
        Utils.matToBitmap(resultMat, resultBmp)
        return resultBmp
    }

    override fun onResume() {
        super.onResume()
        // 连接到OpenCV的回调
        if (OpenCVLoader.initLocal()) {
            mCVCamera!!.enableView()
        } else {
            ToastUtils.showShort("初始化失败")
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // 销毁OpenCV相机
        if (mCVCamera != null) mCVCamera!!.disableView()
    }
}
