package com.learn.antilazy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 零依赖柱状图：每日使用时长，支持高亮某一天与稀疏日期标签。 */
class UsageBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Long> = emptyList()
    private var labels: List<String> = emptyList()
    private var highlightIndex = -1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        strokeWidth = resources.displayMetrics.density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = 10 * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    fun setData(values: List<Long>, labels: List<String>, highlightIndex: Int) {
        require(values.size == labels.size) { "values and labels must match in size" }
        this.values = values
        this.labels = labels
        this.highlightIndex = highlightIndex
        contentDescription = values.joinToString(separator = "，") { formatBarValue(it) }
        invalidate()
    }

    private fun formatBarValue(ms: Long): String = MonitorService.formatDuration(ms)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return

        val density = resources.displayMetrics.density
        val labelHeight = (16 * density).toInt()
        val topPad = (10 * density).toInt()
        val plotH = (height - labelHeight - topPad * 2).coerceAtLeast((8 * density).toInt())
        val baselineY = height - labelHeight.toFloat()

        val slot = width / n.toFloat()
        val maxV = values.max()
        val barWidth = minOf(slot * 0.55f, 28 * density).coerceAtLeast(2 * density)
        val radius = minOf(barWidth / 2f, 3 * density)

        if (maxV > 0L) {
            values.forEachIndexed { i, v ->
                val h = (v.toFloat() / maxV) * plotH
                val left = slot * i + (slot - barWidth) / 2f
                rect.set(left, baselineY - h, left + barWidth, baselineY)
                barPaint.color =
                    if (i == highlightIndex) context.getColor(R.color.brand)
                    else 0xFFE3E3E3.toInt()
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
        }
        canvas.drawLine(0f, baselineY, width.toFloat(), baselineY, baselinePaint)

        labels.forEachIndexed { i, text ->
            if (text.isNotEmpty()) {
                canvas.drawText(
                    text,
                    slot * i + slot / 2f,
                    height - 4 * density,
                    labelPaint
                )
            }
        }
    }
}

/** 占比横条：浅色轨道 + 按比例填充的圆角短条。 */
class ShareBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var fraction = 0f
        set(value) {
            field = value.coerceIn(0.02f, 1f)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEFEFEF.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.brand)
    }
    private val radius = 2 * resources.displayMetrics.density
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val w = width.toFloat() * fraction
        if (w > radius) {
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }
    }
}
