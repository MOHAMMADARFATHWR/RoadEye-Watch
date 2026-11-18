package com.example.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.data.DelegateType
import com.example.data.DetectedPothole
import com.example.data.DetectorUiState
import com.example.data.ModelStatus
import com.example.data.NormalizedRect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DetectionFrameOutput(
  val detections: List<DetectedPothole> = emptyList(),
  val frameWidth: Int = 720,
  val frameHeight: Int = 1280,
  val inferenceLatencyMs: Long = 0L,
  val fps: Float = 0f,
)

class PotholeDetector(
  context: Context,
  initialThreshold: Float = DetectorUiState.DEFAULT_CONFIDENCE_THRESHOLD,
) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val inferenceExecutor = Executors.newSingleThreadExecutor()

  private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Initializing)
  val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

  private val _activeDelegate = MutableStateFlow(DelegateType.CPU)
  val activeDelegate: StateFlow<DelegateType> = _activeDelegate.asStateFlow()

  private val _latestOutput = MutableStateFlow(DetectionFrameOutput())
  val latestOutput: StateFlow<DetectionFrameOutput> = _latestOutput.asStateFlow()

  @Volatile private var objectDetector: ObjectDetector? = null
  @Volatile private var currentThreshold: Float = initialThreshold
  @Volatile private var configuredDetectorThreshold: Float = initialThreshold
  @Volatile private var isClosed: Boolean = false
  @Volatile private var demoFrameWidth: Int = 720
  @Volatile private var demoFrameHeight: Int = 1280

  private var lastInputTimestampMs: Long = 0L
  private var lastResultTimestampMs: Long = 0L
  private var smoothedFps: Float = 0f
  private var demoJob: Job? = null
  private var thresholdRebuildJob: Job? = null

  private data class PendingFrameMeta(
    val startElapsedMs: Long,
    val uprightWidth: Int,
    val uprightHeight: Int,
  )

  private val pendingFrames = ConcurrentHashMap<Long, PendingFrameMeta>()
  private val detectorLock = Any()

  init {
    initialize()
  }

  fun initialize() {
    if (isClosed) return

    if (!assetExists(MODEL_ASSET_NAME)) {
      Log.w(TAG, "Model asset '$MODEL_ASSET_NAME' not found. Entering DEMO MODE.")
      _activeDelegate.value = DelegateType.DEMO
      _modelStatus.value = ModelStatus.MissingAsset(MISSING_ASSET_MESSAGE)
      startDemoSimulation()
      return
    }

    stopDemoSimulation()
    inferenceExecutor.execute {
      if (isClosed) return@execute
      initializeDetectorInternal(currentThreshold)
    }
  }

  private fun assetExists(fileName: String): Boolean {
    return try {
      appContext.assets.open(fileName).use { true }
    } catch (_: IOException) {
      false
    }
  }

  private fun initializeDetectorInternal(threshold: Float) {
    synchronized(detectorLock) {
      closeDetectorLocked()

      val gpuResult = runCatching { buildDetector(Delegate.GPU, threshold) }
      if (gpuResult.isSuccess) {
        objectDetector = gpuResult.getOrNull()
        configuredDetectorThreshold = threshold
        _activeDelegate.value = DelegateType.GPU
        _modelStatus.value = ModelStatus.Ready(DelegateType.GPU)
        Log.i(TAG, "ObjectDetector initialized with GPU delegate (threshold=$threshold)")
        return
      } else {
        Log.w(
          TAG,
          "GPU delegate unavailable, falling back to CPU: ${gpuResult.exceptionOrNull()?.message}",
        )
      }

      val cpuResult = runCatching { buildDetector(Delegate.CPU, threshold) }
      if (cpuResult.isSuccess) {
        objectDetector = cpuResult.getOrNull()
        configuredDetectorThreshold = threshold
        _activeDelegate.value = DelegateType.CPU
        _modelStatus.value = ModelStatus.Ready(DelegateType.CPU)
        Log.i(TAG, "ObjectDetector initialized with CPU delegate (threshold=$threshold)")
      } else {
        val message =
          cpuResult.exceptionOrNull()?.message ?: "Failed to initialize TFLite ObjectDetector"
        Log.e(TAG, "CPU delegate initialization failed: $message")
        _activeDelegate.value = DelegateType.CPU
        _modelStatus.value = ModelStatus.Error(message)
      }
    }
  }

  private fun buildDetector(delegate: Delegate, threshold: Float): ObjectDetector {
    val baseOptions =
      BaseOptions.builder().setModelAssetPath(MODEL_ASSET_NAME).setDelegate(delegate).build()

    val options =
      ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(baseOptions)
        .setRunningMode(RunningMode.LIVE_STREAM)
        .setScoreThreshold(threshold)
        .setMaxResults(MAX_RESULTS)
        .setResultListener(::onLiveStreamResult)
        .setErrorListener(::onLiveStreamError)
        .build()

    return ObjectDetector.createFromOptions(appContext, options)
  }

  fun updateConfidenceThreshold(newThreshold: Float) {
    val clamped =
      newThreshold.coerceIn(
        DetectorUiState.MIN_CONFIDENCE_THRESHOLD,
        DetectorUiState.MAX_CONFIDENCE_THRESHOLD,
      )
    currentThreshold = clamped

    if (_activeDelegate.value == DelegateType.DEMO) {
      return
    }

    if (abs(clamped - configuredDetectorThreshold) < 0.01f) return

    thresholdRebuildJob?.cancel()
    thresholdRebuildJob =
      scope.launch {
        delay(80L)
        if (!isClosed && assetExists(MODEL_ASSET_NAME)) {
          inferenceExecutor.execute {
            if (!isClosed) {
              initializeDetectorInternal(currentThreshold)
            }
          }
        }
      }
  }

  fun analyzeFrame(imageProxy: ImageProxy) {
    try {
      val rotationDegrees = imageProxy.imageInfo.rotationDegrees
      val uprightWidth =
        if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.height else imageProxy.width
      val uprightHeight =
        if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.width else imageProxy.height

      demoFrameWidth = uprightWidth
      demoFrameHeight = uprightHeight

      if (isClosed || _activeDelegate.value == DelegateType.DEMO) {
        return
      }

      val detector = objectDetector ?: return
      val bitmap = imageProxy.toBitmap()
      val mpImage = BitmapImageBuilder(bitmap).build()

      val timestampMs = synchronized(this) {
        val nextTs = max(SystemClock.uptimeMillis(), lastInputTimestampMs + 1L)
        lastInputTimestampMs = nextTs
        nextTs
      }

      if (pendingFrames.size > 64) {
        val cutoff = timestampMs - 2_000L
        pendingFrames.keys.removeIf { it < cutoff }
      }
      pendingFrames[timestampMs] =
        PendingFrameMeta(
          startElapsedMs = SystemClock.uptimeMillis(),
          uprightWidth = uprightWidth,
          uprightHeight = uprightHeight,
        )

      val processingOptions =
        ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()

      detector.detectAsync(mpImage, processingOptions, timestampMs)
    } catch (t: Throwable) {
      Log.e(TAG, "Frame analysis failed: ${t.message}", t)
    } finally {
      imageProxy.close()
    }
  }

  private fun onLiveStreamResult(result: ObjectDetectorResult, input: MPImage) {
    if (isClosed) return

    val now = SystemClock.uptimeMillis()
    val meta = pendingFrames.remove(result.timestampMs())
    val frameWidth = meta?.uprightWidth ?: input.width.coerceAtLeast(1)
    val frameHeight = meta?.uprightHeight ?: input.height.coerceAtLeast(1)
    val latencyMs =
      if (meta != null) (now - meta.startElapsedMs).coerceAtLeast(1L) else 0L

    updateSmoothedFps(now)

    val threshold = currentThreshold
    val mappedDetections =
      result.detections().mapIndexedNotNull { index, detection ->
        val topCategory = detection.categories().maxByOrNull { it.score() } ?: return@mapIndexedNotNull null
        val score = topCategory.score()
        if (score < threshold) return@mapIndexedNotNull null

        val box = detection.boundingBox()
        val normLeft = (box.left / frameWidth.toFloat()).coerceIn(0f, 1f)
        val normTop = (box.top / frameHeight.toFloat()).coerceIn(0f, 1f)
        val normRight = (box.right / frameWidth.toFloat()).coerceIn(normLeft, 1f)
        val normBottom = (box.bottom / frameHeight.toFloat()).coerceIn(normTop, 1f)

        val rect = NormalizedRect(normLeft, normTop, normRight, normBottom)
        val rawLabel = topCategory.categoryName().trim()
        val label = if (rawLabel.isEmpty()) DEFAULT_LABEL else rawLabel.uppercase()

        DetectedPothole(
          id = "det_${result.timestampMs()}_$index",
          label = label,
          confidence = score,
          boundingBox = rect,
          isInDangerZone = rect.intersects(DetectorUiState.DANGER_ZONE),
        )
      }

    _latestOutput.value =
      DetectionFrameOutput(
        detections = mappedDetections,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        inferenceLatencyMs = latencyMs,
        fps = smoothedFps,
      )
  }

  private fun onLiveStreamError(error: RuntimeException) {
    Log.e(TAG, "MediaPipe ObjectDetector error: ${error.message}", error)
    _modelStatus.value = ModelStatus.Error(error.message ?: "Unknown detector error")
  }

  private fun updateSmoothedFps(nowMs: Long) {
    val previous = lastResultTimestampMs
    lastResultTimestampMs = nowMs
    if (previous > 0L) {
      val delta = (nowMs - previous).coerceAtLeast(1L)
      val instantFps = 1_000f / delta.toFloat()
      smoothedFps =
        if (smoothedFps == 0f) instantFps else smoothedFps * 0.8f + instantFps * 0.2f
    }
  }

  private fun startDemoSimulation() {
    demoJob?.cancel()
    demoJob =
      scope.launch {
        var tick = 0L
        while (isActive && !isClosed) {
          val phase = tick * 0.055f
          val cycleProgress = (tick % 110L) / 110f

          // Primary pothole approaching the lower-center danger zone
          val primaryTop = 0.28f + cycleProgress * 0.44f
          val primaryWidth = 0.24f + cycleProgress * 0.14f
          val primaryHeight = 0.10f + cycleProgress * 0.07f
          val primaryLeft = (0.50f + 0.08f * sin(phase)) - primaryWidth * 0.5f
          val primaryConfidence = (0.68f + cycleProgress * 0.24f).coerceAtMost(0.95f)
          val primaryRect =
            NormalizedRect(
              left = primaryLeft.coerceIn(0.05f, 0.90f),
              top = primaryTop.coerceIn(0.15f, 0.82f),
              right = (primaryLeft + primaryWidth).coerceIn(0.10f, 0.95f),
              bottom = (primaryTop + primaryHeight).coerceIn(0.20f, 0.92f),
            )

          // Secondary shoulder pothole (amber caution confidence)
          val secondaryOffset = ((tick + 45L) % 130L) / 130f
          val secTop = 0.24f + secondaryOffset * 0.38f
          val secLeft = 0.14f + 0.04f * sin(phase * 0.7f)
          val secRect =
            NormalizedRect(
              left = secLeft,
              top = secTop,
              right = secLeft + 0.19f,
              bottom = secTop + 0.09f,
            )
          val secConfidence = 0.58f + 0.09f * sin(phase * 1.1f)

          // Occasional subtle low-confidence surface crack (visible when slider < 0.40)
          val minorRect = NormalizedRect(left = 0.66f, top = 0.34f, right = 0.83f, bottom = 0.42f)
          val minorConfidence = 0.36f + 0.04f * sin(phase * 1.4f)

          val threshold = currentThreshold
          val simulated =
            buildList {
                if (primaryConfidence >= threshold) {
                  add(
                    DetectedPothole(
                      id = "demo_primary_${tick / 110L}",
                      label = DEFAULT_LABEL,
                      confidence = primaryConfidence,
                      boundingBox = primaryRect,
                      isInDangerZone = primaryRect.intersects(DetectorUiState.DANGER_ZONE),
                    )
                  )
                }
                if (secConfidence >= threshold) {
                  add(
                    DetectedPothole(
                      id = "demo_secondary_${(tick + 45L) / 130L}",
                      label = DEFAULT_LABEL,
                      confidence = secConfidence,
                      boundingBox = secRect,
                      isInDangerZone = secRect.intersects(DetectorUiState.DANGER_ZONE),
                    )
                  )
                }
                if (minorConfidence >= threshold) {
                  add(
                    DetectedPothole(
                      id = "demo_minor",
                      label = DEFAULT_LABEL,
                      confidence = minorConfidence,
                      boundingBox = minorRect,
                      isInDangerZone = minorRect.intersects(DetectorUiState.DANGER_ZONE),
                    )
                  )
                }
              }
              .take(MAX_RESULTS)

          val simulatedLatency = 12L + (tick % 5L)
          _latestOutput.value =
            DetectionFrameOutput(
              detections = simulated,
              frameWidth = demoFrameWidth,
              frameHeight = demoFrameHeight,
              inferenceLatencyMs = simulatedLatency,
              fps = 24.0f,
            )

          tick++
          delay(42L)
        }
      }
  }

  private fun stopDemoSimulation() {
    demoJob?.cancel()
    demoJob = null
  }

  private fun closeDetectorLocked() {
    runCatching { objectDetector?.close() }
    objectDetector = null
  }

  fun close() {
    isClosed = true
    stopDemoSimulation()
    thresholdRebuildJob?.cancel()
    pendingFrames.clear()
    inferenceExecutor.execute {
      synchronized(detectorLock) { closeDetectorLocked() }
      inferenceExecutor.shutdown()
    }
  }

  companion object {
    private const val TAG = "PotholeDetector"
    const val MODEL_ASSET_NAME = "pothole_model.tflite"
    const val MISSING_ASSET_MESSAGE =
      "Place pothole_model.tflite in app/src/main/assets/"
    private const val MAX_RESULTS = 5
    private const val DEFAULT_LABEL = "POTHOLE"
  }
}
