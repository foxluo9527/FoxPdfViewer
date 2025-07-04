package com.foxluo.pdf.ui.activity

import android.view.View
import com.foxluo.pdf.databinding.ActivityTakePhotoBinding
import com.foxluo.pdf.ui.activity.base.BaseBindingActivity
import com.foxluo.pdf.ui.fragment.takephoto.SingleTakePhotoFragment
import com.foxluo.pdf.ui.fragment.takephoto.TakePhotoCallback
import com.foxluo.pdf.ui.view.FlashMode

class TakePhotoActivity : BaseBindingActivity<ActivityTakePhotoBinding>() {
    private var takePhotoHandle: TakePhotoCallback? = null

    private val takePhotoFragment by lazy {
        SingleTakePhotoFragment().also {
            takePhotoHandle = it
        }
    }

    override fun initBinding() = ActivityTakePhotoBinding.inflate(layoutInflater)


    override fun initStatusBarView() = binding.root

    override fun initView() {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .add(binding.frgContent, takePhotoFragment, "SingleTakePhotoFragment")
            .commit()
    }

    override fun initListener() {
        binding.autoTake.setOnClickListener {
            takePhotoHandle?.let {
                it.setAutoTake(!it.getAutoTake())
                if (it.getAutoTake()) {
                    binding.autoTake.text = "自动拍摄：开启"
                } else {
                    binding.autoTake.text = "自动拍摄：关闭"
                }
            }
        }
        binding.gridLine.setOnClickListener {
            takePhotoHandle?.let {
                it.setGridLineVisible(!it.getGridLineVisible())
                if (it.getGridLineVisible()) {
                    binding.gridLine.text = "网格线：显示"
                } else {
                    binding.gridLine.text = "网格线：隐藏"
                }
            }
        }
        binding.flashMode.setOnClickListener {
            takePhotoHandle?.let {
                val nextMode = when (it.getFlashMode()) {
                    FlashMode.OFF -> FlashMode.AUTO
                    FlashMode.AUTO -> FlashMode.ON
                    FlashMode.ON -> FlashMode.TORCH
                    FlashMode.TORCH -> FlashMode.OFF
                }
                it.setFlashMode(nextMode)
                when (it.getFlashMode()) {
                    FlashMode.OFF -> binding.flashMode.text = "闪光灯：关闭"
                    FlashMode.AUTO -> binding.flashMode.text = "闪光灯：自动"
                    FlashMode.ON -> binding.flashMode.text = "闪光灯：开启"
                    FlashMode.TORCH -> binding.flashMode.text = "闪光灯：常亮"
                }
            }
        }
    }
}