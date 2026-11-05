package com.iacamara.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.iacamara.app.ui.camera.CameraScreen
import com.iacamara.app.ui.diagnostics.DiagnosticsScreen

private enum class Screen { CAMERA, DIAGNOSTICS }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        askedOnce = true
    }
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }

    when {
        !hasCameraPermission -> PermissionScreen(
            askedOnce = askedOnce,
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
        screen == Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = Screen.CAMERA })
        else -> CameraScreen(onOpenDiagnostics = { screen = Screen.DIAGNOSTICS })
    }
}

@Composable
private fun PermissionScreen(askedOnce: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("IA Cámara", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            "Para analizar la escena y hacer fotos, la app necesita usar la cámara. " +
                "Todo se procesa dentro del móvil: la app no tiene acceso a internet.",
            textAlign = TextAlign.Center,
            color = Color.White,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Permitir cámara") }
        if (askedOnce) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) { Text("Abrir ajustes de la app") }
        }
    }
}
