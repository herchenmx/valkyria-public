package com.example.hevycompanion.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize

/**
 * Line chart of one routine's workouts over the trend window — X is time, Y is
 * the selected total. Every workout is a tappable datapoint; the selected one
 * is ringed and dropped to the axis with a guide line, and [onSelect] drives
 * the breakdown panel below the chart.
 *
 * Hand-drawn on a `Canvas` rather than pulled in from a charting library: the
 * whole requirement is one series with tappable points, and the companion has
 * no chart dependency that adding one for this would ride on.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    values: List<Double>,
    selectedIndex: Int,
    lineColor: Color,
    gridColor: Color,
    labelColor: Color,
    highlightColor: Color,
    formatValue: (Double) -> String,
    formatDate: (Long) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = LABEL_SIZE)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .pointerInput(points, values) {
                detectTapGestures { tap ->
                    val geometry = geometryFor(size.toSize(), points, values) ?: return@detectTapGestures
                    val index = TrendChartMath.nearestIndex(
                        xs = points.map { geometry.xFor(it.epochMs) },
                        tapX = tap.x,
                    )
                    if (index != null) onSelect(index)
                }
            },
    ) {
        val geometry = geometryFor(size, points, values) ?: return@Canvas
        drawGrid(geometry, gridColor, textMeasurer, labelStyle, formatValue)
        drawSeries(geometry, points, values, selectedIndex, lineColor, highlightColor)
        drawDateLabels(geometry, points, textMeasurer, labelStyle, formatDate)
    }
}

private val CHART_HEIGHT = 200.dp
private val LABEL_SIZE: TextUnit = 10.sp

/** Room for the Y-axis labels on the left and the date labels underneath. */
private const val PAD_LEFT = 64f
private const val PAD_RIGHT = 16f
private const val PAD_TOP = 16f
private const val PAD_BOTTOM = 32f

private const val POINT_RADIUS = 4f
private const val SELECTED_RADIUS = 7f
private const val GRID_LINES = 3

/**
 * Maps a workout's timestamp and total onto canvas pixels. Built identically
 * for drawing and for hit-testing, so a tap always resolves to the point the
 * user actually sees.
 */
private class ChartGeometry(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val minX: Long,
    val maxX: Long,
    val minY: Double,
    val maxY: Double,
) {
    fun xFor(epochMs: Long): Float {
        if (maxX <= minX) return (left + right) / 2f
        val t = (epochMs - minX).toDouble() / (maxX - minX).toDouble()
        return left + (t * (right - left)).toFloat()
    }

    fun yFor(value: Double): Float {
        if (maxY <= minY) return (top + bottom) / 2f
        val t = (value - minY) / (maxY - minY)
        return bottom - (t * (bottom - top)).toFloat()
    }
}

private fun geometryFor(size: Size, points: List<TrendPoint>, values: List<Double>): ChartGeometry? {
    if (points.isEmpty() || values.size != points.size) return null
    if (size.width <= PAD_LEFT + PAD_RIGHT || size.height <= PAD_TOP + PAD_BOTTOM) return null
    val (minY, maxY) = TrendChartMath.axisBounds(values)
    return ChartGeometry(
        left = PAD_LEFT,
        right = size.width - PAD_RIGHT,
        top = PAD_TOP,
        bottom = size.height - PAD_BOTTOM,
        minX = points.minOf { it.epochMs },
        maxX = points.maxOf { it.epochMs },
        minY = minY,
        maxY = maxY,
    )
}

private fun DrawScope.drawGrid(
    geometry: ChartGeometry,
    gridColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    formatValue: (Double) -> String,
) {
    for (i in 0 until GRID_LINES) {
        val fraction = i.toDouble() / (GRID_LINES - 1)
        val value = geometry.minY + (geometry.maxY - geometry.minY) * fraction
        val y = geometry.yFor(value)
        drawLine(
            color = gridColor,
            start = Offset(geometry.left, y),
            end = Offset(geometry.right, y),
            strokeWidth = 1f,
        )
        val label = textMeasurer.measure(formatValue(value), labelStyle)
        drawText(
            textLayoutResult = label,
            topLeft = Offset(
                x = (geometry.left - 8f - label.size.width).coerceAtLeast(0f),
                y = y - label.size.height / 2f,
            ),
        )
    }
}

private fun DrawScope.drawSeries(
    geometry: ChartGeometry,
    points: List<TrendPoint>,
    values: List<Double>,
    selectedIndex: Int,
    lineColor: Color,
    highlightColor: Color,
) {
    val coords = points.mapIndexed { i, p -> Offset(geometry.xFor(p.epochMs), geometry.yFor(values[i])) }

    if (coords.size > 1) {
        val path = Path().apply {
            moveTo(coords.first().x, coords.first().y)
            coords.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))
    }

    coords.forEachIndexed { i, c ->
        val selected = i == selectedIndex
        drawCircle(
            color = if (selected) highlightColor else lineColor,
            radius = if (selected) SELECTED_RADIUS else POINT_RADIUS,
            center = c,
        )
        if (selected) {
            // Guide from the selected point down to the axis, so the panel
            // below is visibly tied to this workout.
            drawLine(
                color = highlightColor,
                start = Offset(c.x, c.y),
                end = Offset(c.x, geometry.bottom),
                strokeWidth = 1f,
            )
            drawCircle(
                color = highlightColor,
                radius = SELECTED_RADIUS + 4f,
                center = c,
                style = Stroke(width = 2f),
            )
        }
    }
}

/** First and last dates only — a year of workouts can't label every point. */
private fun DrawScope.drawDateLabels(
    geometry: ChartGeometry,
    points: List<TrendPoint>,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    formatDate: (Long) -> String,
) {
    if (points.isEmpty()) return
    val y = geometry.bottom + 8f
    val first = textMeasurer.measure(formatDate(geometry.minX), labelStyle)
    drawText(textLayoutResult = first, topLeft = Offset(geometry.left, y))
    if (points.size > 1 && geometry.maxX > geometry.minX) {
        val last = textMeasurer.measure(formatDate(geometry.maxX), labelStyle)
        drawText(
            textLayoutResult = last,
            topLeft = Offset(geometry.right - last.size.width, y),
        )
    }
}
