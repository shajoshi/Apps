package com.sj.gpsutil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Configuration for a semi-circular gauge dial.
 *
 * @param value Current value to display on the needle
 * @param minValue Minimum value (maps to left end of arc)
 * @param maxValue Maximum value (maps to right end of arc)
 * @param label Label text shown below the value (e.g. "LEAN")
 * @param unit Unit suffix shown after the value (e.g. "°")
 * @param leftColor Color for the left side of the arc (negative/left values)
 * @param rightColor Color for the right side of the arc (positive/right values)
 * @param needleColors Gradient colors for the needle [base, middle, tip]
 * @param tickInterval Degrees between minor tick marks
 * @param majorTickInterval Degrees between major tick marks
 * @param bgColor Background color of the gauge face
 * @param tickColor Color of tick marks
 * @param textColor Color of the value text
 * @param labelColor Color of the label text
 * @param centerIsZero If true, 0 is at the top center and value sweeps left/right. If false, minValue is at left and value sweeps from left.
 */
data class GaugeConfig(
    val value: Float = 0f,
    val minValue: Float = -45f,
    val maxValue: Float = 45f,
    val label: String = "",
    val unit: String = "°",
    val leftColor: Color = Color(0xFF42A5F5),
    val leftColorDark: Color = Color(0xFF1565C0),
    val rightColor: Color = Color(0xFFFF9800),
    val rightColorDark: Color = Color(0xFFE65100),
    val needleColors: List<Color> = listOf(Color(0xFFD32F2F), Color(0xFFFF5722), Color(0xFFFFCC80)),
    val tickInterval: Int = 5,
    val majorTickInterval: Int = 15,
    val bgColor: Color = Color(0xFF0D1B2A),
    val tickColor: Color = Color(0xCCFFFFFF),
    val textColor: Color = Color.White,
    val labelColor: Color = Color.Gray,
    val centerIsZero: Boolean = true,
    val scale: Float = 1.0f
)

/**
 * A reusable semi-circular gauge composable.
 * Draws a 180° arc (top half) with tick marks, a tapered needle, center hub,
 * and value/label text below the hub.
 */
@Composable
fun SemiCircularGauge(
    config: GaugeConfig,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        drawSemiGauge(textMeasurer, config)
    }
}

private fun DrawScope.drawSemiGauge(textMeasurer: TextMeasurer, config: GaugeConfig) {
    val canvasW = size.width
    val canvasH = size.height
    // Semi-circle: flat edge at bottom, arc curves upward.
    // Pivot (needle hub) sits on the flat baseline.
    val gaugeRadius = minOf(canvasW / 2f, canvasH * 0.75f) * 0.80f * config.scale
    val arcWidth = gaugeRadius * 0.12f
    // Place pivot so the semi-circle fills the upper portion with room for text below
    val pivotCenter = Offset(canvasW / 2f, canvasH * 0.55f)

    // --- Dark background semi-circle (top half) ---
    // In Compose Canvas: 0°=3 o'clock, 90°=6 o'clock, 180°=9 o'clock, 270°=12 o'clock
    // Top half arc: from 180° (9 o'clock) sweeping +180° clockwise through 270° (12) to 0°/360° (3 o'clock)
    val bgPath = Path().apply {
        moveTo(pivotCenter.x - gaugeRadius, pivotCenter.y)
        arcTo(
            rect = Rect(
                pivotCenter.x - gaugeRadius,
                pivotCenter.y - gaugeRadius,
                pivotCenter.x + gaugeRadius,
                pivotCenter.y + gaugeRadius
            ),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false
        )
        close()
    }
    drawPath(bgPath, config.bgColor)

    // Outer border arc (top half)
    drawArc(
        color = Color(0xFF2C3E50),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(pivotCenter.x - gaugeRadius, pivotCenter.y - gaugeRadius),
        size = Size(gaugeRadius * 2, gaugeRadius * 2),
        style = Stroke(width = 2f)
    )
    // Baseline (flat bottom edge)
    drawLine(
        color = Color(0xFF2C3E50),
        start = Offset(pivotCenter.x - gaugeRadius, pivotCenter.y),
        end = Offset(pivotCenter.x + gaugeRadius, pivotCenter.y),
        strokeWidth = 2f
    )

    // --- Arc track (dim background, top half) ---
    val arcInset = arcWidth / 2
    val arcRect = Rect(
        pivotCenter.x - gaugeRadius + arcInset,
        pivotCenter.y - gaugeRadius + arcInset,
        pivotCenter.x + gaugeRadius - arcInset,
        pivotCenter.y + gaugeRadius - arcInset
    )
    drawArc(
        color = Color(0xFF1A2A3A),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(arcRect.left, arcRect.top),
        size = Size(arcRect.width, arcRect.height),
        style = Stroke(width = arcWidth, cap = StrokeCap.Round)
    )

    // --- Colored arc fill based on value ---
    val range = config.maxValue - config.minValue
    val clampedValue = config.value.coerceIn(config.minValue, config.maxValue)

    if (config.centerIsZero) {
        // Center (0) is at 270° canvas (12 o'clock).
        // Left (minValue) = 180° canvas (9 o'clock), Right (maxValue) = 360° canvas (3 o'clock).
        // Negative values sweep counterclockwise from 270°, positive sweep clockwise from 270°.
        if (clampedValue < 0) {
            val fraction = abs(clampedValue) / abs(config.minValue)
            val sweepDeg = fraction * 90f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(config.leftColor, config.leftColorDark, config.leftColor),
                    center = pivotCenter
                ),
                startAngle = 270f,
                sweepAngle = -sweepDeg,
                useCenter = false,
                topLeft = Offset(arcRect.left, arcRect.top),
                size = Size(arcRect.width, arcRect.height),
                style = Stroke(width = arcWidth, cap = StrokeCap.Round)
            )
        } else if (clampedValue > 0) {
            val fraction = clampedValue / config.maxValue
            val sweepDeg = fraction * 90f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(config.rightColor, config.rightColorDark, config.rightColor),
                    center = pivotCenter
                ),
                startAngle = 270f,
                sweepAngle = sweepDeg,
                useCenter = false,
                topLeft = Offset(arcRect.left, arcRect.top),
                size = Size(arcRect.width, arcRect.height),
                style = Stroke(width = arcWidth, cap = StrokeCap.Round)
            )
        }
    } else {
        // Non-centered: minValue at left (180°), maxValue at right (360°)
        val fraction = (clampedValue - config.minValue) / range
        val sweepDeg = fraction * 180f
        val fillColor = if (fraction < 0.5f) config.leftColor else config.rightColor
        drawArc(
            color = fillColor,
            startAngle = 180f,
            sweepAngle = sweepDeg,
            useCenter = false,
            topLeft = Offset(arcRect.left, arcRect.top),
            size = Size(arcRect.width, arcRect.height),
            style = Stroke(width = arcWidth, cap = StrokeCap.Round)
        )
    }

    // --- Tick marks (top half: angles from 180° to 360°) ---
    val tickInnerR = gaugeRadius - arcWidth - 6f
    val tickOuterR = gaugeRadius - arcWidth / 2
    val majorTickOuterR = tickOuterR + 3f
    // Map value range to canvas angles: minValue -> 180°, maxValue -> 360°
    for (valDeg in config.minValue.toInt()..config.maxValue.toInt() step config.tickInterval) {
        val isMajor = valDeg % config.majorTickInterval == 0
        val fraction = (valDeg - config.minValue) / range
        // minValue maps to 180° (left), maxValue maps to 360° (right)
        val canvasAngle = 180.0 + fraction * 180.0
        val angleRad = Math.toRadians(canvasAngle)
        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()
        val innerR = if (isMajor) tickInnerR - 6f else tickInnerR
        val outerR = if (isMajor) majorTickOuterR else tickOuterR
        val startPt = Offset(pivotCenter.x + innerR * cosA, pivotCenter.y + innerR * sinA)
        val endPt = Offset(pivotCenter.x + outerR * cosA, pivotCenter.y + outerR * sinA)
        drawLine(
            color = if (isMajor) config.tickColor else config.tickColor.copy(alpha = 0.4f),
            start = startPt,
            end = endPt,
            strokeWidth = if (isMajor) 3f else 1.5f,
            cap = StrokeCap.Round
        )

        // Draw value labels at major ticks
        if (isMajor) {
            val labelText = "${valDeg}${config.unit}"
            val labelStyle = TextStyle(color = config.tickColor.copy(alpha = 0.7f), fontSize = 10.sp)
            val labelLayout = textMeasurer.measure(labelText, labelStyle)
            val labelR = tickInnerR - 14f
            val lx = pivotCenter.x + labelR * cosA - labelLayout.size.width / 2f
            val ly = pivotCenter.y + labelR * sinA - labelLayout.size.height / 2f
            if (lx >= 0 && ly >= 0 && lx + labelLayout.size.width <= size.width && ly + labelLayout.size.height <= size.height) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = labelText,
                    topLeft = Offset(lx, ly),
                    style = labelStyle
                )
            }
        }
    }

    // --- Tapered needle ---
    val needleLength = gaugeRadius * 0.72f
    val needleBaseW = 14f
    val needleTipW = 3f

    // Needle rotation: 0 = pointing straight up (270° canvas).
    // The 180° arc spans -90° to +90° of rotation from vertical.
    // For centerIsZero: map value fraction to ±90° rotation.
    // For non-centered: map value to -90..+90 range.
    val needleRotation = if (config.centerIsZero) {
        if (clampedValue >= 0) {
            (clampedValue / config.maxValue) * 90f
        } else {
            -(abs(clampedValue) / abs(config.minValue)) * 90f
        }
    } else {
        val fraction = (clampedValue - config.minValue) / range
        -90f + fraction * 180f
    }

    rotate(degrees = needleRotation, pivot = pivotCenter) {
        val needlePath = Path().apply {
            moveTo(pivotCenter.x - needleBaseW / 2, pivotCenter.y)
            lineTo(pivotCenter.x - needleTipW / 2, pivotCenter.y - needleLength)
            lineTo(pivotCenter.x + needleTipW / 2, pivotCenter.y - needleLength)
            lineTo(pivotCenter.x + needleBaseW / 2, pivotCenter.y)
            close()
        }
        drawPath(
            path = needlePath,
            brush = Brush.verticalGradient(
                colors = config.needleColors,
                startY = pivotCenter.y,
                endY = pivotCenter.y - needleLength
            )
        )
    }

    // --- Center hub ---
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF616161), Color(0xFF212121)),
            center = pivotCenter,
            radius = 14f
        ),
        radius = 12f,
        center = pivotCenter
    )
    drawCircle(
        color = Color(0xFF9E9E9E),
        radius = 12f,
        center = pivotCenter,
        style = Stroke(width = 1.5f)
    )

    // --- Value text below the baseline ---
    val valueFontSize = 28.sp
    val valueText = "${"%.1f".format(abs(config.value))}${config.unit}"
    val valueStyle = TextStyle(color = config.textColor, fontSize = valueFontSize, fontWeight = FontWeight.Bold)
    val valueLayout = textMeasurer.measure(valueText, valueStyle)
    val valueX = (pivotCenter.x - valueLayout.size.width / 2f).coerceIn(0f, (size.width - valueLayout.size.width).coerceAtLeast(0f))
    val valueY = (pivotCenter.y + 10f).coerceIn(0f, (size.height - valueLayout.size.height).coerceAtLeast(0f))
    drawText(textMeasurer = textMeasurer, text = valueText, topLeft = Offset(valueX, valueY), style = valueStyle)

    // --- Label text below value ---
    if (config.label.isNotEmpty()) {
        val labelStyle = TextStyle(color = config.labelColor, fontSize = 14.sp)
        val labelLayout = textMeasurer.measure(config.label, labelStyle)
        val labelX = (pivotCenter.x - labelLayout.size.width / 2f).coerceIn(0f, (size.width - labelLayout.size.width).coerceAtLeast(0f))
        val labelY = (valueY + valueLayout.size.height + 2f).coerceIn(0f, (size.height - labelLayout.size.height).coerceAtLeast(0f))
        drawText(textMeasurer = textMeasurer, text = config.label, topLeft = Offset(labelX, labelY), style = labelStyle)
    }
}
