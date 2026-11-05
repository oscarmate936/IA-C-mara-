package com.iacamara.app.ui.camera

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaActionSound
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iacamara.app.ShutterKeys
import com.iacamara.app.camera.CameraEngine
import com.iacamara.app.camera.FlashSetting
import com.iacamara.app.camera.Lens
import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.sensors.LevelReading
import com.iacamara.app.sensors.LevelSensor
import com.iacamara.app.settings.AppPrefs
import com.iacamara.app.ui.theme.Amber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CameraScreen(onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val prefs = remember { AppPrefs(context.applicationContext) }
    val engine = remember { CameraEngine(context.applicationContext) }
    val state by engine.state.collectAsStateWithLifecycle()

    var showGrid by remember { mutableStateOf(prefs.grid) }
    var showLevel by remember { mutableStateOf(prefs.level) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) {
        engine.start(lifecycleOwner, previewView.surfaceProvider)
    }
    DisposableEffect(Unit) {
        ShutterKeys.enabled = true
        onDispose {
            ShutterKeys.enabled = false
            engine.stop()
        }
    }

    // Orientación física del móvil: gira los iconos y corrige la rotación de la foto.
    var deviceDegrees by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val snapped = when (orientation) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
                if (snapped != deviceDegrees) {
                    deviceDegrees = snapped
                    engine.setTargetRotation(
                        when (snapped) {
                            90 -> Surface.ROTATION_270
                            180 -> Surface.ROTATION_180
                            270 -> Surface.ROTATION_90
                            else -> Surface.ROTATION_0
                        },
                    )
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    val iconRotation by animateFloatAsState(
        targetValue = when (deviceDegrees) {
            90 -> -90f
            180 -> 180f
            270 -> 90f
            else -> 0f
        },
        label = "iconRotation",
    )

    // Nivel (giroscopio/gravedad).
    val levelSensor = remember { LevelSensor(context.applicationContext) }
    val levelFlow = remember(showLevel) { if (showLevel) levelSensor.readings() else emptyFlow() }
    val levelState: State<LevelReading?> = levelFlow.collectAsState(initial = null)
    val isLevel by remember { derivedStateOf { levelState.value?.isLevel == true } }
    LaunchedEffect(isLevel) {
        if (isLevel) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Disparo.
    val flashAlpha = remember { Animatable(0f) }
    val shutterSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { shutterSound.release() } }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val currentState by rememberUpdatedState(state)
    val shoot: () -> Unit = {
        if (currentState.ready && !currentState.capturing) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            }
            scope.launch {
                flashAlpha.snapTo(0.8f)
                flashAlpha.animateTo(0f, tween(300))
            }
            engine.takePhoto()
        }
    }
    LaunchedEffect(Unit) {
        ShutterKeys.presses.collect { shoot() }
    }

    // Enfoque táctil.
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }

    // Mensajes temporales.
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4000)
            engine.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Barra superior.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotatingIconButton(
                icon = when (state.flash) {
                    FlashSetting.OFF -> Icons.Filled.FlashOff
                    FlashSetting.AUTO -> Icons.Filled.FlashAuto
                    FlashSetting.ON -> Icons.Filled.FlashOn
                },
                description = "Flash",
                rotation = iconRotation,
                active = state.flash != FlashSetting.OFF,
                enabled = state.hasFlash,
                onClick = { engine.cycleFlash() },
            )
            RotatingIconButton(
                icon = Icons.Filled.GridOn,
                description = "Rejilla",
                rotation = iconRotation,
                active = showGrid,
                onClick = {
                    showGrid = !showGrid
                    prefs.grid = showGrid
                },
            )
            RotatingIconButton(
                icon = Icons.Filled.Straighten,
                description = "Nivel",
                rotation = iconRotation,
                active = showLevel,
                onClick = {
                    showLevel = !showLevel
                    prefs.level = showLevel
                },
            )
            RotatingIconButton(
                icon = Icons.Outlined.Info,
                description = "Diagnóstico de cámara",
                rotation = iconRotation,
                onClick = onOpenDiagnostics,
            )
        }

        // Visor 3:4 (formato completo del sensor).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // Capa de gestos: tocar para enfocar, pellizcar para hacer zoom.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                            engine.focus(point)
                            focusPoint = offset
                            focusKey++
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            if (zoomChange != 1f) engine.zoomBy(zoomChange)
                        }
                    },
            )

            if (showGrid) GridOverlay()
            if (showLevel) LevelOverlay(levelState)
            FocusRing(focusPoint, focusKey)

            // Destello al disparar.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = flashAlpha.value }
                    .background(Color.White),
            )

            // Información: resolución real y modo.
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.captureMegapixels?.let { mp -> InfoChip(String.format(Locale.US, "%.0f MP", mp)) }
                if (state.mode != PhotoMode.FOTO) InfoChip(state.mode.label)
            }

            if (state.capturing && state.mode != PhotoMode.FOTO) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Procesando… mantén el móvil quieto", color = Color.White, fontSize = 13.sp)
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCCB00020))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            ZoomBar(
                zoom = state.zoomRatio,
                minZoom = state.minZoom,
                maxZoom = state.maxZoom,
                rotation = iconRotation,
                onSelect = { engine.setZoom(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }

        // Modos y controles inferiores.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModeSelector(
                modes = state.availableModes,
                selected = state.mode,
                onSelect = { engine.setMode(it) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LastPhotoThumbnail(
                    uri = state.lastPhotoUri,
                    rotation = iconRotation,
                    onClick = { state.lastPhotoUri?.let { openPhoto(context, it) } },
                )
                ShutterButton(capturing = state.capturing, enabled = state.ready, onClick = shoot)
                Box(modifier = Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    RotatingIconButton(
                        icon = Icons.Filled.Cameraswitch,
                        description = if (state.lens == Lens.BACK) "Cambiar a cámara frontal" else "Cambiar a cámara trasera",
                        rotation = iconRotation,
                        enabled = state.hasFrontCamera,
                        onClick = { engine.switchLens() },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun openPhoto(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "image/jpeg")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No hay visor de fotos instalado: no se hace nada.
    }
}
