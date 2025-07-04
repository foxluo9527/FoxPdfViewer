package com.foxluo.pdf.ui.activity.base

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewbinding.ViewBinding
import com.blankj.utilcode.util.BarUtils
import com.foxluo.pdf.R

abstract class BaseBindingActivity<Binding : ViewBinding> : AppCompatActivity() {
    val statusBarHeight by lazy {
        BarUtils.getStatusBarHeight()
    }

    val binding by lazy {
        initBinding()
    }

    private val loadingDialog by lazy {
        AlertDialog
            .Builder(this)
            .setView(R.layout.dialog_loading)
            .setCancelable(false)
            .create()
    }

    fun setLoading(loading: Boolean) {
        if (loading) {
            loadingDialog.show()
        } else {
            loadingDialog.dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        initView()
        initListener()
        initObserver()
        initData()
        initStatusBarView()?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val stateBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.setPadding(stateBars.left, stateBars.top, stateBars.right, stateBars.bottom)
                insets
            }
        }
    }

    open fun initView() {

    }

    open fun initListener() {

    }

    open fun initObserver() {

    }

    open fun initData() {

    }

    open fun initStatusBarView(): View? {
        return null
    }

    abstract fun initBinding(): Binding
}