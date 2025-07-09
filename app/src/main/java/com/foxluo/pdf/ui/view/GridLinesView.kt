package com.foxluo.pdf.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 网格线绘制视图
 */
class GridLinesView(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGridLines(canvas)
    }

    private fun drawGridLines(canvas: Canvas) {
        val horizontalLines = 2
        val verticalLines = 2
        val cellWidth = width.toFloat() / (verticalLines + 1)
        val cellHeight = height.toFloat() / (horizontalLines + 1)

        // 绘制水平线
        for (i in 1..horizontalLines) {
            val y = cellHeight * i
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }

        // 绘制垂直线
        for (i in 1..verticalLines) {
            val x = cellWidth * i
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }
    }
}