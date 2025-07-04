package com.foxluo.pdf.ui.fragment.takephoto

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.blankj.utilcode.util.ToastUtils
import com.foxluo.pdf.databinding.FragmentSingleTakePhotoBinding
import com.foxluo.pdf.ui.fragment.base.BaseBindingFragment
import com.foxluo.pdf.ui.view.FlashMode
import org.opencv.android.OpenCVLoader

@OptIn(ExperimentalCamera2Interop::class)
class SingleTakePhotoFragment : BaseBindingFragment<FragmentSingleTakePhotoBinding>(),
    TakePhotoCallback {
    override fun initBinding() = FragmentSingleTakePhotoBinding.inflate(layoutInflater)

    override fun initView() {
        if (OpenCVLoader.initLocal()) {
            binding.cameraView.setLifecycleOwner(this)
        } else {
            ToastUtils.showShort("初始化失败")
            activity?.finish()
        }
    }

    override fun takePhoto() {

    }

    override fun getAutoTake() = binding.cameraView.isAutoCaptureEnabled

    override fun setAutoTake(auto: Boolean) {
        binding.cameraView.isAutoCaptureEnabled = auto
    }

    override fun getFlashMode() = binding.cameraView.getFlashMode()

    override fun setFlashMode(mode: FlashMode) = binding.cameraView.setFlashMode(mode)

    override fun getGridLineVisible() = binding.cameraView.isGridLinesEnabled

    override fun setGridLineVisible(visible: Boolean) {
        binding.cameraView.isGridLinesEnabled = visible
    }

    override fun getTakeVoiceEnable() = binding.cameraView.isCaptureSoundEnabled

    override fun setTakeVoiceEnable(enable: Boolean) {
        binding.cameraView.isCaptureSoundEnabled = enable
    }
}