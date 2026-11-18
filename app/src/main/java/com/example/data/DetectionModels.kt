package com.example.data

import androidx.compose.runtime.Immutable

@Immutable
data class NormalizedRect(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
) {
  val centerX: Float
    get() = (left + right) * 0.5f

  val centerY: Float
    get() = (top + bottom) * 0.5f

  fun intersects(other: NormalizedRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

@Immutable
data class DetectedPothole(
  val id: String,
  val label: String,
  val confidence: Float,
  val boundingBox: NormalizedRect,
  val isInDangerZone: Boolean,
)

enum class DelegateType {
  GPU,
  CPU,
  DEMO,
}

sealed interface ModelStatus {
  data object Initializing : ModelStatus

  data class Ready(val delegate: DelegateType) : ModelStatus

  data class MissingAsset(val message: String) : ModelStatus

  data class Error(val message: String) : ModelStatus
}

@Immutable
data class DetectorUiState(
  val modelStatus: ModelStatus = ModelStatus.Initializing,
  val activeDelegate: DelegateType = DelegateType.CPU,
  val detections: List<DetectedPothole> = emptyList(),
  val frameWidth: Int = 720,
  val frameHeight: Int = 1280,
  val inferenceLatencyMs: Long = 0L,
  val fps: Float = 0f,
  val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
  val hazardAlertEnabled: Boolean = true,
  val hazardAlertActive: Boolean = false,
  val sessionPotholeCount: Int = 0,
  val isDemoMode: Boolean = false,
) {
  companion object {
    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.45f
    const val MIN_CONFIDENCE_THRESHOLD = 0.25f
    const val MAX_CONFIDENCE_THRESHOLD = 0.90f
    const val HIGH_CONFIDENCE_THRESHOLD = 0.75f

    val DANGER_ZONE = NormalizedRect(left = 0.22f, top = 0.55f, right = 0.78f, bottom = 0.92f)
  }
}
