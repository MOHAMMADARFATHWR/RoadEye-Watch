package com.example.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.DetectedPothole
import com.example.data.DetectorUiState
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.HazardCrimson
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OnAccentDark
import com.example.ui.theme.TacticalCyan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun BoundingBoxOverlay(
  detections: List<DetectedPothole>,
  frameWidth: Int,
  frameHeight: Int,
  hazardAlertActive: Boolean,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val dangerZoneLabel = stringResource(R.string.danger_zone_tag)

  Canvas(modifier = modifier.fillMaxSize().testTag("bounding_box_overlay")) {
    val safeFrameW = frameWidth.coerceAtLeast(1).toFloat()
    val safeFrameH = frameHeight.coerceAtLeast(1).toFloat()

    // Matches CameraX PreviewView.ScaleType.FILL_CENTER
    val scaleFactor = max(size.width / safeFrameW, size.height / safeFrameH)
    val scaledWidth = safeFrameW * scaleFactor
    val scaledHeight = safeFrameH * scaleFactor
    val offsetX = (size.width - scaledWidth) * 0.5f
    val offsetY = (size.height - scaledHeight) * 0.5f

    drawDangerZoneReticle(
      hazardAlertActive = hazardAlertActive,
      dangerZoneLabel = dangerZoneLabel,
      textMeasurer = textMeasurer,
    )

    for (detection in detections) {
      val box = detection.boundingBox
      val left = (offsetX + box.left * scaledWidth).coerceIn(0f, size.width)
      val top = (offsetY + box.top * scaledHeight).coerceIn(0f, size.height)
      val right = (offsetX + box.right * scaledWidth).coerceIn(left, size.width)
      val bottom = (offsetY + box.bottom * scaledHeight).coerceIn(top, size.height)

      val boxW = right - left
      val boxH = bottom - top
      if (boxW < 4f || boxH < 4f) continue

      val isHighConfidence = detection.confidence >= DetectorUiState.HIGH_CONFIDENCE_THRESHOLD
      val boxColor = if (isHighConfidence) HazardCrimson else CautionAmber

      // Translucent box fill for contrast against asphalt
      drawRoundRect(
        color = boxColor.copy(alpha = if (detection.isInDangerZone && isHighConfidence) 0.18f else 0.08f),
        topLeft = Offset(left, top),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
      )

      // Primary rounded outline
      drawRoundRect(
        color = boxColor.copy(alpha = 0.85f),
        topLeft = Offset(left, top),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
        style = Stroke(width = 2.dp.toPx()),
      )

      // Corner bracket accents
      drawCornerBrackets(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        color = boxColor,
        strokeWidth = 3.75.dp.toPx(),
        maxLength = 18.dp.toPx(),
      )

      // Label pill (e.g., "POTHOLE 88%")
      val confidencePercent = (detection.confidence * 100f).roundToInt().coerceIn(0, 100)
      val labelText = "${detection.label} $confidencePercent%"
      drawLabelPill(
        text = labelText,
        boxLeft = left,
        boxTop = top,
        pillColor = boxColor,
        textColor = if (isHighConfidence) Color.White else OnAccentDark,
        textMeasurer = textMeasurer,
      )
    }
  }
}

private fun DrawScope.drawDangerZoneReticle(
  hazardAlertActive: Boolean,
  dangerZoneLabel: String,
  textMeasurer: TextMeasurer,
) {
  val dz = DetectorUiState.DANGER_ZONE
  val left = dz.left * size.width
  val top = dz.top * size.height
  val right = dz.right * size.width
  val bottom = dz.bottom * size.height
  val width = right - left
  val height = bottom - top
  val zoneColor =
    if (hazardAlertActive) HazardCrimson.copy(alpha = 0.85f) else TacticalCyan.copy(alpha = 0.35f)

  drawRoundRect(
    color = zoneColor,
    topLeft = Offset(left, top),
    size = Size(width, height),
    cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
    style =
      Stroke(
        width = if (hazardAlertActive) 2.2.dp.toPx() else 1.2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
      ),
  )

  drawCornerBrackets(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    color = if (hazardAlertActive) HazardCrimson else TacticalCyan.copy(alpha = 0.7f),
    strokeWidth = 2.5.dp.toPx(),
    maxLength = 16.dp.toPx(),
  )

  val measured =
    textMeasurer.measure(
      text = dangerZoneLabel,
      style =
        TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          letterSpacing = 1.sp,
        ),
    )

  val textX = left + (width - measured.size.width) * 0.5f
  val textY = (top - measured.size.height - 4.dp.toPx()).coerceAtLeast(0f)
  drawText(
    textLayoutResult = measured,
    color = if (hazardAlertActive) HazardCrimson else TacticalCyan.copy(alpha = 0.75f),
    topLeft = Offset(textX, textY),
  )
}

private fun DrawScope.drawCornerBrackets(
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  color: Color,
  strokeWidth: Float,
  maxLength: Float,
) {
  val cornerLenW = min(maxLength, (right - left) * 0.28f)
  val cornerLenH = min(maxLength, (bottom - top) * 0.28f)
  val path =
    Path().apply {
      // Top-left
      moveTo(left, top + cornerLenH)
      lineTo(left, top)
      lineTo(left + cornerLenW, top)
      // Top-right
      moveTo(right - cornerLenW, top)
      lineTo(right, top)
      lineTo(right, top + cornerLenH)
      // Bottom-left
      moveTo(left, bottom - cornerLenH)
      lineTo(left, bottom)
      lineTo(left + cornerLenW, bottom)
      // Bottom-right
      moveTo(right - cornerLenW, bottom)
      lineTo(right, bottom)
      lineTo(right, bottom - cornerLenH)
    }

  drawPath(
    path = path,
    color = color,
    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
  )
}

private fun DrawScope.drawLabelPill(
  text: String,
  boxLeft: Float,
  boxTop: Float,
  pillColor: Color,
  textColor: Color,
  textMeasurer: TextMeasurer,
) {
  val textLayout =
    textMeasurer.measure(
      text = text,
      style =
        TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          letterSpacing = 0.4.sp,
        ),
    )

  val padH = 8.dp.toPx()
  val padV = 4.dp.toPx()
  val pillW = textLayout.size.width + padH * 2f
  val pillH = textLayout.size.height + padV * 2f

  val pillLeft = boxLeft.coerceIn(0f, (size.width - pillW).coerceAtLeast(0f))
  val pillTop =
    if (boxTop - pillH - 4.dp.toPx() >= 0f) {
      boxTop - pillH - 4.dp.toPx()
    } else {
      (boxTop + 4.dp.toPx()).coerceAtMost((size.height - pillH).coerceAtLeast(0f))
    }

  drawRoundRect(
    color = pillColor,
    topLeft = Offset(pillLeft, pillTop),
    size = Size(pillW, pillH),
    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
  )

  drawText(
    textLayoutResult = textLayout,
    color = textColor,
    topLeft = Offset(pillLeft + padH, pillTop + padV),
  )
}
