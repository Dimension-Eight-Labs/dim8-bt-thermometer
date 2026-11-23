package com.dim8labs.btthermometer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.dim8labs.btthermometer.R
import com.dim8labs.btthermometer.core.Excursion
import com.dim8labs.btthermometer.core.GraphAxis
import com.dim8labs.btthermometer.core.HistoryGraph
import com.dim8labs.btthermometer.core.SparklinePolicy
import com.dim8labs.btthermometer.core.TemperatureColor
import com.dim8labs.btthermometer.core.Units

/**
 * Compact 30-minute probe history with sparse axes.
 *
 * The line uses a vertical gradient built from the temperature palette. Height on
 * the graph is temperature, so every point gets its correct heat color with one
 * path draw. Scaling, gaps, and marker selection live in [HistoryGraph]. Work is
 * bounded by the fixed history size.
 */
class HistoryGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val labelTextSize = resources.getDimension(R.dimen.graph_label_text)
    private val gutter = 28f * density
    private val labelHeight = 14f * density
    private val markerSize = 3.5f * density

    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.border)
        strokeWidth = 1f
    }
    // A dotted baseline marks missing time without changing the card background.
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.getColor(R.color.text_muted)
        pathEffect = DashPathEffect(floatArrayOf(2f * density, 3f * density), 0f)
    }
    private val gapPath = Path()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted)
        textSize = labelTextSize
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = context.getColor(R.color.background)
    }
    private val path = Path()
    private val markerPath = Path()

    private var values: List<Int?> = emptyList()
    private var excursions: List<Excursion> = emptyList()
    private var scaleCenterC: Int? = null
    private var bandC = 0
    private var fahrenheit = true

    fun setData(
        nextValues: List<Int?>,
        nextExcursions: List<Excursion>,
        nextScaleCenterC: Int?,
        nextBandC: Int,
        nextFahrenheit: Boolean,
    ) {
        if (nextValues == values && nextExcursions == excursions && nextScaleCenterC == scaleCenterC &&
            nextBandC == bandC && nextFahrenheit == fahrenheit
        ) {
            return
        }
        values = nextValues
        excursions = nextExcursions
        scaleCenterC = nextScaleCenterC
        bandC = nextBandC
        fahrenheit = nextFahrenheit
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = gutter
        val right = width - 4f * density
        val top = 5f * density
        val bottom = height - labelHeight
        if (right <= left || bottom <= top) return

        val axis = HistoryGraph.axis(values, fahrenheit)
        drawGrid(canvas, axis, left, top, right, bottom)
        if (axis == null || values.size < 2) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No recent history", (left + right) / 2f, (top + bottom) / 2f, labelPaint)
            return
        }

        fun xOf(index: Int) = left + (right - left) * index / (values.size - 1)
        fun yOf(centiC: Int) =
            bottom - (bottom - top) * axis.fraction(Units.toDisplay(centiC, fahrenheit)).toFloat()

        gapPath.rewind()
        HistoryGraph.gaps(values).forEach {
            gapPath.moveTo(xOf(it.startIndex), bottom)
            gapPath.lineTo(xOf(it.endIndex), bottom)
        }
        canvas.drawPath(gapPath, gapPaint)
        applyHeatShader(axis, top, bottom)
        drawLine(canvas, ::xOf, ::yOf)
        HistoryGraph.markers(values, excursions).forEach {
            drawMarker(canvas, xOf(it.index), yOf(values[it.index]!!), it.excursion)
        }
    }

    private fun drawGrid(canvas: Canvas, axis: GraphAxis?, left: Float, top: Float, right: Float, bottom: Float) {
        val middleY = (top + bottom) / 2f
        listOf(top, middleY, bottom).forEach { canvas.drawLine(left, it, right, it, gridPaint) }
        listOf(left, (left + right) / 2f, right).forEach { canvas.drawLine(it, top, it, bottom, gridPaint) }

        val baseline = height - 3f * density
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("-30m", left, baseline, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("-15m", (left + right) / 2f, baseline, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Now", right, baseline, labelPaint)

        if (axis == null) return
        val center = (labelPaint.descent() + labelPaint.ascent()) / 2f
        val labelX = left - 4f * density
        // The top label is nudged down so it is not clipped by the view edge.
        canvas.drawText("${axis.high}°", labelX, top - center * 0.6f, labelPaint)
        canvas.drawText("${axis.mid}°", labelX, middleY - center, labelPaint)
        canvas.drawText("${axis.low}°", labelX, bottom, labelPaint)
    }

    /** Without a target or limit there is no scale center, so the line stays neutral. */
    private fun applyHeatShader(axis: GraphAxis, top: Float, bottom: Float) {
        val center = scaleCenterC
        val shader = if (center == null) {
            null
        } else {
            val colors = IntArray(HEAT_SAMPLES) { index ->
                val display = axis.low + (axis.high - axis.low) * index / (HEAT_SAMPLES - 1.0)
                TemperatureColor.forTemperature(Units.fromDisplay(display, fahrenheit), center, bandC)
            }
            LinearGradient(0f, bottom, 0f, top, colors, null, Shader.TileMode.CLAMP)
        }
        linePaint.shader = shader
        dotPaint.shader = shader
        linePaint.color = TemperatureColor.NEUTRAL
        dotPaint.color = TemperatureColor.NEUTRAL
    }

    private fun drawLine(canvas: Canvas, xOf: (Int) -> Float, yOf: (Int) -> Float) {
        path.rewind()
        var previous = -1
        var segmentHasLine = true
        values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            val x = xOf(index)
            val y = yOf(value)
            if (previous >= 0 && SparklinePolicy.connects(previous, index)) {
                path.lineTo(x, y)
                segmentHasLine = true
            } else {
                // A segment with one sample has no line, so mark it with a dot.
                if (!segmentHasLine) canvas.drawCircle(xOf(previous), yOf(values[previous]!!), DOT_DP * density, dotPaint)
                path.moveTo(x, y)
                segmentHasLine = false
            }
            previous = index
        }
        if (!segmentHasLine && previous >= 0) {
            canvas.drawCircle(xOf(previous), yOf(values[previous]!!), DOT_DP * density, dotPaint)
        }
        canvas.drawPath(path, linePaint)
    }

    /** Triangle direction repeats the meaning of color: up is high, down is low. */
    private fun drawMarker(canvas: Canvas, x: Float, y: Float, excursion: Excursion) {
        val high = excursion == Excursion.HIGH
        val tip = if (high) -markerSize else markerSize
        markerPath.rewind()
        markerPath.moveTo(x, y + tip)
        markerPath.lineTo(x - markerSize, y - tip * 0.7f)
        markerPath.lineTo(x + markerSize, y - tip * 0.7f)
        markerPath.close()
        markerPaint.color = if (high) TemperatureColor.HOT else TemperatureColor.COLD
        canvas.drawPath(markerPath, markerOutline)
        canvas.drawPath(markerPath, markerPaint)
    }

    private companion object {
        const val HEAT_SAMPLES = 24
        const val DOT_DP = 1.5f
    }
}
