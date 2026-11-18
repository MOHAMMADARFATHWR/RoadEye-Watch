package com.example.ui.camera

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.DelegateType
import com.example.data.DetectorUiState
import com.example.data.ModelStatus
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.HazardCrimson
import com.example.ui.theme.HudBorderSubtle
import com.example.ui.theme.HudGlassDark
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.TacticalCyan
import com.example.ui.theme.TacticalSurfaceElevated
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun DetectorHudScreen(
  viewModel: PotholeDetectorViewModel,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  Box(modifier = modifier.fillMaxSize().background(ObsidianBg)) {
    CameraPreviewView(
      onAnalyzeFrame = viewModel::analyzeFrame,
      modifier = Modifier.fillMaxSize(),
    )

    BoundingBoxOverlay(
      detections = uiState.detections,
      frameWidth = uiState.frameWidth,
      frameHeight = uiState.frameHeight,
      hazardAlertActive = uiState.hazardAlertActive,
      modifier = Modifier.fillMaxSize(),
    )

    AnimatedVisibility(
      visible = uiState.hazardAlertActive && uiState.hazardAlertEnabled,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.fillMaxSize(),
    ) {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .border(width = 3.dp, color = HazardCrimson.copy(alpha = 0.75f))
      )
    }

    // Top compact status bar
    Column(
      modifier =
        Modifier.align(Alignment.TopCenter)
          .fillMaxWidth()
          .windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
          )
          .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      TopStatusBar(
        activeDelegate = uiState.activeDelegate,
        latencyMs = uiState.inferenceLatencyMs,
        fps = uiState.fps,
      )

      val status = uiState.modelStatus
      if (status is ModelStatus.MissingAsset) {
        Spacer(modifier = Modifier.height(8.dp))
        MissingAssetPill(message = status.message)
      } else if (status is ModelStatus.Error) {
        Spacer(modifier = Modifier.height(8.dp))
        ErrorStatusPill(message = status.message)
      }
    }

    // Bottom tactical control deck
    Box(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .fillMaxWidth()
          .windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
          )
          .padding(horizontal = 16.dp, vertical = 12.dp),
      contentAlignment = Alignment.Center,
    ) {
      BottomControlDeck(
        uiState = uiState,
        onConfidenceChanged = viewModel::onConfidenceThresholdChanged,
        onHazardAlertToggled = viewModel::onHazardAlertToggled,
        onResetCount = viewModel::onResetSessionCount,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun CameraPreviewView(
  onAnalyzeFrame: (ImageProxy) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  val previewView = remember {
    PreviewView(context).apply {
      layoutParams =
        ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
      scaleType = PreviewView.ScaleType.FILL_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
  }

  DisposableEffect(lifecycleOwner) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    val mainExecutor = ContextCompat.getMainExecutor(context)

    cameraProviderFuture.addListener(
      {
        runCatching {
            val cameraProvider = cameraProviderFuture.get()
            val preview =
              Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

            val imageAnalysis =
              ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                  analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    onAnalyzeFrame(imageProxy)
                  }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
              lifecycleOwner,
              cameraSelector,
              preview,
              imageAnalysis,
            )
          }
          .onFailure { error ->
            Log.e("CameraPreviewView", "CameraX bindToLifecycle failed: ${error.message}", error)
          }
      },
      mainExecutor,
    )

    onDispose {
      runCatching {
        if (cameraProviderFuture.isDone) {
          cameraProviderFuture.get().unbindAll()
        }
      }
      analysisExecutor.shutdown()
    }
  }

  Box(modifier = modifier) {
    // Subtle dark road-horizon backdrop visible if camera hardware is unavailable
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(Color(0xFF0D1219), Color(0xFF161E29), Color(0xFF0B0F15))
            )
          )
    )
    AndroidView(
      factory = { previewView },
      modifier = Modifier.fillMaxSize().testTag("camera_preview"),
    )
  }
}

@Composable
private fun TopStatusBar(
  activeDelegate: DelegateType,
  latencyMs: Long,
  fps: Float,
  modifier: Modifier = Modifier,
) {
  val badgeLabel =
    when (activeDelegate) {
      DelegateType.GPU -> stringResource(R.string.delegate_gpu)
      DelegateType.CPU -> stringResource(R.string.delegate_cpu)
      DelegateType.DEMO -> stringResource(R.string.delegate_demo)
    }
  val badgeColor =
    when (activeDelegate) {
      DelegateType.GPU -> ReadyEmerald
      DelegateType.CPU -> TacticalCyan
      DelegateType.DEMO -> CautionAmber
    }

  Surface(
    modifier = modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("top_status_bar"),
    shape = RoundedCornerShape(16.dp),
    color = HudGlassDark,
    border = BorderStroke(1.dp, HudBorderSubtle),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      // Delegate Badge
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = badgeColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f)),
        modifier = Modifier.testTag("delegate_badge"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(modifier = Modifier.size(7.dp).background(badgeColor, CircleShape))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = badgeLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = badgeColor,
          )
        }
      }

      // Latency & FPS telemetry
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        TelemetryMetric(
          value = "${latencyMs}ms",
          label = "LAT",
          modifier = Modifier.testTag("latency_metric"),
        )
        TelemetryMetric(
          value = "${fps.roundToInt()} FPS",
          label = "RATE",
          modifier = Modifier.testTag("fps_metric"),
        )
      }
    }
  }
}

@Composable
private fun TelemetryMetric(value: String, label: String, modifier: Modifier = Modifier) {
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(5.dp))
    Text(
      text = value,
      style =
        MaterialTheme.typography.labelMedium.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
        ),
      color = MaterialTheme.colorScheme.onBackground,
    )
  }
}

@Composable
private fun MissingAssetPill(message: String) {
  Surface(
    modifier = Modifier.widthIn(max = 600.dp).testTag("missing_asset_banner"),
    shape = RoundedCornerShape(10.dp),
    color = CautionAmber.copy(alpha = 0.18f),
    border = BorderStroke(1.dp, CautionAmber.copy(alpha = 0.6f)),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.labelSmall,
      color = CautionAmber,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun ErrorStatusPill(message: String) {
  Surface(
    modifier = Modifier.widthIn(max = 600.dp).testTag("error_status_banner"),
    shape = RoundedCornerShape(10.dp),
    color = HazardCrimson.copy(alpha = 0.22f),
    border = BorderStroke(1.dp, HazardCrimson.copy(alpha = 0.65f)),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.labelSmall,
      color = Color.White,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun BottomControlDeck(
  uiState: DetectorUiState,
  onConfidenceChanged: (Float) -> Unit,
  onHazardAlertToggled: (Boolean) -> Unit,
  onResetCount: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.testTag("bottom_control_deck"),
    shape = RoundedCornerShape(20.dp),
    color = HudGlassDark,
    border = BorderStroke(1.dp, HudBorderSubtle),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Session counter chip + Reset Count button
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = TacticalSurfaceElevated,
          border = BorderStroke(1.dp, HudBorderSubtle),
          modifier = Modifier.testTag("session_counter_chip"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.ReportProblem,
              contentDescription = null,
              tint = if (uiState.hazardAlertActive) HazardCrimson else CautionAmber,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "${uiState.sessionPotholeCount}",
              style =
                MaterialTheme.typography.titleMedium.copy(
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                ),
              color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (uiState.sessionPotholeCount == 1) "Pothole" else "Potholes",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        FilledTonalButton(
          onClick = onResetCount,
          modifier = Modifier.heightIn(min = 48.dp).testTag("reset_count_button"),
          shape = RoundedCornerShape(12.dp),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          colors =
            ButtonDefaults.filledTonalButtonColors(
              containerColor = TacticalSurfaceElevated,
              contentColor = MaterialTheme.colorScheme.onBackground,
            ),
        ) {
          Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.reset_count),
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }

      // Confidence Threshold Slider
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.confidence_threshold_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
          )
          Text(
            text = "${(uiState.confidenceThreshold * 100f).roundToInt()}%",
            style =
              MaterialTheme.typography.labelMedium.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
            color = CautionAmber,
            modifier = Modifier.testTag("confidence_value_text"),
          )
        }

        Slider(
          value = uiState.confidenceThreshold,
          onValueChange = onConfidenceChanged,
          valueRange =
            DetectorUiState.MIN_CONFIDENCE_THRESHOLD..DetectorUiState.MAX_CONFIDENCE_THRESHOLD,
          colors =
            SliderDefaults.colors(
              thumbColor = CautionAmber,
              activeTrackColor = CautionAmber,
              inactiveTrackColor = TacticalSurfaceElevated,
            ),
          modifier = Modifier.fillMaxWidth().testTag("confidence_slider"),
        )
      }

      // Audio & Haptic Danger Zone Alert Switch
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Icon(
            imageVector =
              if (uiState.hazardAlertEnabled) Icons.Rounded.NotificationsActive
              else Icons.Rounded.NotificationsOff,
            contentDescription = null,
            tint =
              if (uiState.hazardAlertEnabled) HazardCrimson
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = stringResource(R.string.hazard_alert_label),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = stringResource(R.string.hazard_alert_sublabel),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Switch(
          checked = uiState.hazardAlertEnabled,
          onCheckedChange = onHazardAlertToggled,
          colors =
            SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = HazardCrimson,
              uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
              uncheckedTrackColor = TacticalSurfaceElevated,
            ),
          modifier = Modifier.testTag("hazard_alert_switch"),
        )
      }
    }
  }
}
