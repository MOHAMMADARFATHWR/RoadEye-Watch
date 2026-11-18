package com.example.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class HazardAlertController(context: Context) {

  private val appContext = context.applicationContext

  private val vibrator: Vibrator? by lazy {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val manager =
            appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
          manager?.defaultVibrator
        } else {
          @Suppress("DEPRECATION")
          appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
      }
      .getOrNull()
  }

  private var toneGenerator: ToneGenerator? = null
  private var lastAlertElapsedMs: Long = 0L

  fun triggerIfCooldownElapsed(cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastAlertElapsedMs < cooldownMs) return
    lastAlertElapsedMs = now

    triggerHaptic()
    triggerTone()
  }

  private fun triggerHaptic() {
    runCatching {
      val vib = vibrator ?: return
      if (!vib.hasVibrator()) return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vib.vibrate(VibrationEffect.createOneShot(110L, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION") vib.vibrate(110L)
      }
    }
  }

  private fun triggerTone() {
    runCatching {
        val generator =
          toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).also {
            toneGenerator = it
          }
        generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 140)
      }
      .onFailure { error ->
        Log.w(TAG, "Unable to play hazard alert tone: ${error.message}")
      }
  }

  fun release() {
    runCatching { toneGenerator?.release() }
    toneGenerator = null
  }

  companion object {
    private const val TAG = "HazardAlertController"
    private const val DEFAULT_COOLDOWN_MS = 1_200L
  }
}
