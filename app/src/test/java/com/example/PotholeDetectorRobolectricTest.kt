package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.DelegateType
import com.example.data.DetectorUiState
import com.example.data.ModelStatus
import com.example.data.NormalizedRect
import com.example.ml.PotholeDetector
import com.example.ui.camera.CameraPermissionScreen
import com.example.ui.theme.RoadWatchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PotholeDetectorRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `missing model asset falls back to demo mode without crashing`() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val detector = PotholeDetector(app)

    try {
      assertEquals(DelegateType.DEMO, detector.activeDelegate.value)
      assertEquals(
        ModelStatus.MissingAsset(PotholeDetector.MISSING_ASSET_MESSAGE),
        detector.modelStatus.value,
      )

      // Allow demo simulation loop to emit at least one frame
      delay(150L)
      val output = detector.latestOutput.value
      assertTrue(output.detections.isNotEmpty())
      assertTrue(output.inferenceLatencyMs > 0L)
      assertTrue(output.fps > 0f)

      // Raising confidence threshold above simulated max should filter detections cleanly
      detector.updateConfidenceThreshold(0.96f)
      delay(120L)
      assertTrue(detector.latestOutput.value.detections.all { it.confidence >= 0.90f })
    } finally {
      detector.close()
    }
  }

  @Test
  fun `danger zone intersection geometry detects lower-center hazards`() {
    val insideDangerZone = NormalizedRect(left = 0.40f, top = 0.65f, right = 0.60f, bottom = 0.80f)
    val outsideDangerZone = NormalizedRect(left = 0.05f, top = 0.10f, right = 0.20f, bottom = 0.25f)

    assertTrue(insideDangerZone.intersects(DetectorUiState.DANGER_ZONE))
    assertFalse(outsideDangerZone.intersects(DetectorUiState.DANGER_ZONE))
  }

  @Test
  fun `permission screen shows grant button and handles click`() {
    var requested = false
    composeTestRule.setContent {
      RoadWatchTheme {
        CameraPermissionScreen(
          hasDeniedOnce = false,
          shouldShowRationale = false,
          onRequestPermission = { requested = true },
          onOpenSettings = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("grant_camera_permission_button")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule.onNodeWithTag("grant_camera_permission_button").performClick()
    assertTrue(requested)
  }
}
