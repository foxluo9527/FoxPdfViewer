package com.foxluo.pdf.ui.fragment.takephoto

import com.foxluo.pdf.ui.view.FlashMode

interface TakePhotoCallback {
    fun takePhoto()

    fun getAutoTake(): Boolean

    fun setAutoTake(auto: Boolean)

    fun getFlashMode(): FlashMode

    fun setFlashMode(mode: FlashMode): Boolean

    fun getGridLineVisible(): Boolean

    fun setGridLineVisible(visible: Boolean)

    fun getTakeVoiceEnable(): Boolean

    fun setTakeVoiceEnable(enable: Boolean)
}