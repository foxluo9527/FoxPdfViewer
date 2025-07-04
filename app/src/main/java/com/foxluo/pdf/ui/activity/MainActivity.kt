package com.foxluo.pdf.ui.activity

import android.content.Intent
import com.foxluo.pdf.databinding.ActivityMainBinding
import com.foxluo.pdf.ui.activity.base.BaseBindingActivity


class MainActivity : BaseBindingActivity<ActivityMainBinding>() {
    override fun initBinding() = ActivityMainBinding.inflate(layoutInflater)

    override fun initListener() {
        binding.takePhoto.setOnClickListener {
            startActivity(Intent(this, TakePhotoActivity::class.java))
        }
    }
}