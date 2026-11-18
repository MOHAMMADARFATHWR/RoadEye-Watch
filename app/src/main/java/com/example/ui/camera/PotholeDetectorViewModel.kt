package com.example.ui.camera

import android.app.Application
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DetectedPothole
import com.example.data.DetectorUiState
import com.example.data.HazardAlertController
import com.example.ml.PotholeDetector
import kotlin.math.hypot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PotholeDetectorViewModel(application: Application) : AndroidViewModel(application) {

  private val detector = PotholeDetector(application)
  private val alertController = HazardAlertController(application)

  private val _confidenceThreshold = MutableStateFlow(DetectorUiState.DEFAULT_CONFIDENCE_THRESHOLD)
  private val _hazardAlertEnabled = MutableStateFlow(true)
  private val _sessionPotholeCount = MutableStateFlow(0)

  private val _uiState = MutableStateFlow(DetectorUiState())
  val uiState: StateFlow<DetectorUiState> = _uiState.asStateFlow()

  private data class TrackedHazard(var centerX: Float, var centerY: Float, var lastSeenMs: Long)

  private val activeTrackedHazards = mutableListOf<TrackedHazard>()
  private val trackingLock = Any()

  init {
    viewModelScope.launch {
      combine(
          detector.modelStatus,
          detector.activeDelegate,
          detector.latestOutput,
          _confidenceThreshold,
          _hazardAlertEnabled,
        ) { status, delegate, output, threshold, alertEnabled ->
          val nowMs = SystemClock.elapsedRealtime()
          val newDetectionsCount = updateSessionTracking(output.detections, nowMs)
          if (newDetectionsCount > 0) {
            _sessionPotholeCount.update { it + newDetectionsCount }
          }

          val dangerTriggered =
            output.detections.any {
              it.confidence >= DetectorUiState.HIGH_CONFIDENCE_THRESHOLD && it.isInDangerZone
            }

          if (alertEnabled && dangerTriggered) {
            alertController.triggerIfCooldownElapsed()
          }

          DetectorUiState(
            modelStatus = status,
            activeDelegate = delegate,
            detections = output.detections,
            frameWidth = output.frameWidth,
            frameHeight = output.frameHeight,
            inferenceLatencyMs = output.inferenceLatencyMs,
            fps = output.fps,
            confidenceThreshold = threshold,
            hazardAlertEnabled = alertEnabled,
            hazardAlertActive = dangerTriggered,
            sessionPotholeCount = _sessionPotholeCount.value,
            isDemoMode = delegate == com.example.data.DelegateType.DEMO,
          )
        }
        .collect { nextState -> _uiState.value = nextState }
    }
  }

  private fun updateSessionTracking(detections: List<DetectedPothole>, nowMs: Long): Int {
    synchronized(trackingLock) {
      activeTrackedHazards.removeAll { nowMs - it.lastSeenMs > TRACKING_EXPIRY_MS }
      var newlyDetected = 0

      for (det in detections) {
        val cx = det.boundingBox.centerX
        val cy = det.boundingBox.centerY
        val match =
          activeTrackedHazards.firstOrNull { tracked ->
            hypot((cx - tracked.centerX).toDouble(), (cy - tracked.centerY).toDouble()) <
              TRACKING_MATCH_RADIUS
          }

        if (match != null) {
          match.centerX = cx
          match.centerY = cy
          match.lastSeenMs = nowMs
        } else {
          activeTrackedHazards.add(TrackedHazard(cx, cy, nowMs))
          newlyDetected++
        }
      }
      return newlyDetected
    }
  }

  fun analyzeFrame(imageProxy: ImageProxy) {
    detector.analyzeFrame(imageProxy)
  }

  fun onConfidenceThresholdChanged(newValue: Float) {
    val clamped =
      newValue.coerceIn(
        DetectorUiState.MIN_CONFIDENCE_THRESHOLD,
        DetectorUiState.MAX_CONFIDENCE_THRESHOLD,
      )
    _confidenceThreshold.value = clamped
    detector.updateConfidenceThreshold(clamped)
  }

  fun onHazardAlertToggled(enabled: Boolean) {
    _hazardAlertEnabled.value = enabled
  }

  fun onResetSessionCount() {
    synchronized(trackingLock) { activeTrackedHazards.clear() }
    _sessionPotholeCount.value = 0
    _uiState.update { it.copy(sessionPotholeCount = 0) }
  }

  override fun onCleared() {
    super.onCleared()
    detector.close()
    alertController.release()
  }

  companion object {
    private const val TRACKING_EXPIRY_MS = 1_100L
    private const val TRACKING_MATCH_RADIUS = 0.18
  }
}
