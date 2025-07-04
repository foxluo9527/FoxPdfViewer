package com.foxluo.pdf.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.Keyboard
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.RelativeLayout

/**
 * 可拖动、旋转和缩放的文字输入视图
 */
@SuppressLint("ClickableViewAccessibility")
class DraggableTextInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {
    // 文字输入框
    private val editText: EditText

    // 关闭按钮
    private val closeButton: ImageView

    // 旋转/缩放按钮
    private val rotateButton: ImageView

    // 拖动相关变量
    private var isDragging = false
    private var startX = 0f
    private var startY = 0f
    private var initialX = 0f
    private var initialY = 0f

    // 旋转缩放相关变量
    private var isScaling = false
    private var startDistance = 0f
    private var startAngle = 0f
    private var currentScale = 1f
    private var currentRotation = 0f
    private var pivotX = 0f
    private var pivotY = 0f

    // 文字样式
    var textColor = Color.BLACK
        set(value) {
            field = value
            editText.setTextColor(value)
        }
    var textSize = 30f
        set(value) {
            field = value
            editText.textSize = value
        }

    // 回调接口
    private var onCloseListener: ((DraggableTextInputView) -> Unit)? = null
    private var onTextChangeListener: (() -> Unit)? = null

    // 手势检测器
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())

    init {
        // 设置背景
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(2, Color.BLUE)
            cornerRadius = 4.dpToPx().toFloat()
        }

        // 初始化EditText
        editText = EditText(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16.dpToPx(), 16.dpToPx(), 40.dpToPx(), 40.dpToPx())
            }
            setTextColor(textColor)
            textSize = this@DraggableTextInputView.textSize
            setBackgroundColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "输入文字..."
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onTextChangeListener?.invoke()
                }
            })
        }
        addView(editText)

        // 初始化关闭按钮
        closeButton = ImageView(context).apply {
            layoutParams = LayoutParams(36.dpToPx(), 36.dpToPx()).apply {
                addRule(ALIGN_PARENT_LEFT)
                addRule(ALIGN_PARENT_TOP)
                leftMargin = (-18).dpToPx()
                topMargin = (-18).dpToPx()
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setOnClickListener {
                hideKeyboard()
                onCloseListener?.invoke(this@DraggableTextInputView)
            }
        }
        addView(closeButton)

        // 初始化旋转按钮
        rotateButton = ImageView(context).apply {
            layoutParams = LayoutParams(36.dpToPx(), 36.dpToPx()).apply {
                addRule(ALIGN_PARENT_RIGHT)
                addRule(ALIGN_PARENT_BOTTOM)
                rightMargin = -18.dpToPx()
                bottomMargin = -18.dpToPx()
            }
            setImageResource(android.R.drawable.ic_menu_rotate)
        }
        addView(rotateButton)

        // 设置触摸监听
        setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        // 初始显示键盘
        post { showKeyboard() }
    }

    /**
     * 获取文字内容
     */
    val text: String
        get() = editText.text.toString()

    /**
     * 获取文字画笔
     */
    val textPaint: Paint
        get() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = this@DraggableTextInputView.textSize
            typeface = editText.typeface
        }

    /**
     * 设置关闭监听器
     */
    fun setOnCloseListener(listener: (DraggableTextInputView) -> Unit) {
        onCloseListener = listener
    }

    /**
     * 设置文字变化监听器
     */
    fun setOnTextChangeListener(listener: () -> Unit) {
        onTextChangeListener = listener
    }

    /**
     * 将视图内容转换为Bitmap
     */
    fun getBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        return bitmap
    }

    /**
     * 显示键盘
     */
    private fun showKeyboard() {
        editText.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 隐藏键盘
     */
    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    /**
     * 计算两点之间的距离
     */
    private fun distanceBetweenPoints(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /**
     * 计算两点之间的角度
     */
    private fun angleBetweenPoints(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            .toFloat() * 180 / Math.PI.toFloat()
    }

    /**
     * 手势监听器
     */
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            startX = e.rawX
            startY = e.rawY
            initialX = x
            initialY = y
            pivotX = width / 2f
            pivotY = height / 2f
            return true
        }


        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (!isScaling) {
                isDragging = true
                val newX = initialX + (e2.rawX - startX)
                val newY = initialY + (e2.rawY - startY)
                x = newX
                y = newY
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isDragging && !isScaling) {
                showKeyboard()
            }
            isDragging = false
            isScaling = false
            return true
        }
    }

    /**
     * 缩放监听器
     */
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            startDistance = distanceBetweenPoints(
                detector.focusX,
                detector.focusY,
                detector.currentSpanX,
                detector.currentSpanY
            )
            startAngle = angleBetweenPoints(
                detector.focusX,
                detector.focusY,
                detector.currentSpanX,
                detector.currentSpanY
            )
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newDistance = distanceBetweenPoints(
                detector.focusX,
                detector.focusY,
                detector.currentSpanX,
                detector.currentSpanY
            )
            val newAngle = angleBetweenPoints(
                detector.focusX,
                detector.focusY,
                detector.currentSpanX,
                detector.currentSpanY
            )

            // 计算缩放比例
            val scaleFactor = newDistance / startDistance
            currentScale *= scaleFactor
            currentScale = currentScale.coerceIn(0.5f, 3.0f)

            // 计算旋转角度
            currentRotation += newAngle - startAngle

            // 应用变换
            applyTransformation()

            startDistance = newDistance
            startAngle = newAngle
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    }

    /**
     * 应用缩放和旋转变换
     */
    private fun applyTransformation() {
        pivotX = width / 2f
        pivotY = height / 2f
        scaleX = currentScale
        scaleY = currentScale
        rotation = currentRotation

        // 调整文字大小
        editText.textSize = textSize * currentScale
    }

    /**
     * dp转px
     */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}