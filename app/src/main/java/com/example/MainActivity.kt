package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.camera.CameraPermissionScreen
import com.example.ui.camera.DetectorHudScreen
import com.example.ui.camera.PotholeDetectorViewModel
import com.example.ui.theme.RoadWatchTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )

    setContent {
      RoadWatchTheme {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        var hasCameraPermission by remember {
          mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
              PackageManager.PERMISSION_GRANTED
          )
        }
        var hasDeniedOnce by remember { mutableStateOf(false) }

        val permissionLauncher =
          rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
          ) { granted ->
            hasCameraPermission = granted
            if (!granted) {
              hasDeniedOnce = true
            }
          }

        // Re-check permission when returning from system App Settings
        DisposableEffect(lifecycleOwner) {
          val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
              hasCameraPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                  PackageManager.PERMISSION_GRANTED
            }
          }
          lifecycleOwner.lifecycle.addObserver(observer)
          onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (hasCameraPermission) {
          val detectorViewModel: PotholeDetectorViewModel = viewModel()
          DetectorHudScreen(viewModel = detectorViewModel)
        } else {
          val shouldShowRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(
              this,
              Manifest.permission.CAMERA,
            )
          CameraPermissionScreen(
            hasDeniedOnce = hasDeniedOnce,
            shouldShowRationale = shouldShowRationale,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
              val intent =
                Intent(
                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.fromParts("package", packageName, null),
                )
              startActivity(intent)
            },
          )
        }
      }
    }
  }
}
