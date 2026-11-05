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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import android.os.SystemClock
import androidx.compose.material.icons.filled.CameraEnhance
import androidx.compose.runtime.mutableFloatStateOf
import com.iacamara.app.composition.CompositionEngine
import com.iacamara.app.composition.CompositionInput
import com.iacamara.app.composition.GuideStyle
import com.iacamara.app.composition.GuideStyleStabilizer
import com.iacamara.app.composition.Guidance
import com.iacamara.app.director.Tip
import com.iacamara.app.director.CaptureDirector
import com.iacamara.app.director.CaptureStrategy
import com.iacamara.app.camera.Processing
import com.iacamara.app.perception.Condition
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.iacamara.app.director.Direction
import com.iacamara.app.director.DirectorCaps
import com.iacamara.app.director.DirectorSession
import com.iacamara.app.director.TipAction
import com.iacamara.app.perception.MotionSensor
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.PerceptionAnalyzer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

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
    var iaEnabled by remember { mutableStateOf(prefs.ia) }
    var showHistogram by remember { mutableStateOf(prefs.histogram) }

    // IA: percepción en vivo (Capa 1) + Director (Capa 2).
    val motionSensor = remember { MotionSensor(context.applicationContext) }
    val analyzer = remember { PerceptionAnalyzer(context.applicationContext, engine.captureMetadata, motionSensor) }
    val perception by analyzer.perception.collectAsStateWithLifecycle()
    val iaError by analyzer.error.collectAsStateWithLifecycle()
    val director = remember { DirectorSession() }
    val auto = remember { AutoFocusState() }
    var direction by remember { mutableStateOf<Direction?>(null) }
    var guidance by remember { mutableStateOf<Guidance?>(null) }
    // Guía de composición fija elegida por el usuario (null = la elige la IA según la escena).
    var forcedGuide by remember {
        mutableStateOf(prefs.guide?.let { name -> GuideStyle.entries.firstOrNull { it.name == name } })
    }
    val guideStabilizer = remember { GuideStyleStabilizer() }
    var smartShutter by remember { mutableStateOf(prefs.smartShutter) }
    var autoProgress by remember { mutableFloatStateOf(0f) }
    val smart = remember { SmartShutterState() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) {
        engine.setAnalyzer(analyzer)
        engine.eyeScorer = { bitmap -> analyzer.eyesOpenScore(bitmap) }
        engine.aestheticScorer = { bitmap -> analyzer.aestheticScore(bitmap) }
        engine.start(lifecycleOwner, previewView.surfaceProvider)
    }
    DisposableEffect(Unit) {
        ShutterKeys.enabled = true
        onDispose {
            ShutterKeys.enabled = false
            engine.release()
            analyzer.close()
        }
    }
    // Protección de la aceleración: se sabe si la app se cerró de golpe con la cámara a la vista.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> analyzer.setForeground(true)
                Lifecycle.Event.ON_STOP -> analyzer.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(iaEnabled) {
        if (iaEnabled) motionSensor.start()
        onDispose { motionSensor.stop() }
    }
    SideEffect {
        analyzer.frontCamera = state.lens == Lens.FRONT
        analyzer.enabled = iaEnabled
        analyzer.zoomRatio = state.zoomRatio
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
                    analyzer.deviceDegrees = snapped
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
            // El Director IA decide la técnica: noche, HDR, mejor de una ráfaga o foto normal.
            engine.capture(
                CaptureDirector.plan(
                    mode = currentState.mode,
                    perception = perception,
                    iaEnabled = iaEnabled,
                    flashOn = currentState.flash == FlashSetting.ON,
                ),
            )
        }
    }
    LaunchedEffect(Unit) {
        ShutterKeys.presses.collect { shoot() }
    }

    // Enfoque táctil.
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }

    // Director IA: cada análisis decide enfoque, exposición y consejo, y los aplica a la cámara.
    LaunchedEffect(Unit) {
        analyzer.perception.collect { p ->
            val s = currentState
            if (s.bindGeneration != auto.generation) auto.reset(s.bindGeneration)
            if (p == null || !iaEnabled || !s.analysisActive) {
                direction = null
                guidance = null
                autoProgress = 0f
                smart.reset()
                return@collect
            }
            val d = director.update(
                p,
                DirectorCaps(
                    front = s.lens == Lens.FRONT,
                    mode = s.mode,
                    availableModes = s.availableModes,
                    zoomRatio = s.zoomRatio,
                    maxZoom = s.maxZoom,
                    hasOpticalTele = s.hasOpticalTele,
                    minEv = s.minEv,
                    maxEv = s.maxEv,
                ),
            )
            direction = d

            // Coach de composición (Fase 3).
            val front = s.lens == Lens.FRONT
            val level = levelState.value
            val mainFace = p.subject?.let { subject -> p.faceDetails.maxByOrNull { it.box.iou(subject.box) } }
                ?: p.faceDetails.firstOrNull()
            val compositionInput = CompositionInput(
                scene = p.scene,
                subjectKind = p.subject?.kind,
                subjectBox = p.subject?.box,
                faces = p.faces,
                // La mirada se mide sin espejo; el usuario ve la frontal en espejo.
                gazeX = mainFace?.gazeX?.let { if (front) -it else it },
                saliency = p.saliency,
                rollDegrees = level?.takeIf { !it.isFlat }?.deviationDegrees,
                horizonY = p.light?.horizonY,
                interestingSky = (p.light?.skyBlue ?: 0f) >= 0.25f || (p.light?.warmTop ?: 0f) >= 0.2f,
                highlightClip = p.light?.highlightClip ?: 0f,
                shadowClip = p.light?.shadowClip ?: 0f,
                blurRisk = Condition.TEMBLOR in p.conditions,
                front = front,
                deviceDegrees = deviceDegrees,
                namedObjects = p.namedObjects,
                structure = p.structure,
                pose = p.pose,
                aesthetic = p.aesthetic,
                // Más de ~37° hacia arriba o hacia abajo: techo, cielo o suelo, sin horizonte.
                steepView = level != null && !level.isFlat && abs(level.nz) > 0.6f,
            )
            // La guía automática solo cambia si la escena la pide varias veces seguidas (sin parpadeos).
            val style = forcedGuide ?: guideStabilizer.update(CompositionEngine.chooseStyle(compositionInput))
            val g = CompositionEngine.evaluate(compositionInput.copy(style = style))
            guidance = g
            engine.rememberAutoExposure()

            if (!s.ready) return@collect
            val now = SystemClock.elapsedRealtime()

            // Disparo inteligente: encuadre bueno, móvil quieto y todos con los ojos abiertos.
            if (smartShutter && !s.capturing) {
                val eyesOk = p.faces.isEmpty() ||
                    (p.faceDetails.size >= p.faces.size && p.faceDetails.all { it.eyesOpen })
                val ready = g.isGood && eyesOk &&
                    Condition.TEMBLOR !in p.conditions && Condition.MOVIMIENTO !in p.conditions
                autoProgress = smart.update(ready, now)
                if (autoProgress >= 1f) {
                    smart.fired(now)
                    autoProgress = 0f
                    shoot()
                }
            } else if (!smartShutter) {
                autoProgress = 0f
                smart.reset()
            }
            // Durante una ráfaga (noche, HDR, mejor foto) no se toca el enfoque ni la exposición:
            // cambiarían entre fotos y estropearían el horquillado o el alineado.
            if (s.capturing) return@collect
            if (now - auto.lastManualFocusMs > MANUAL_FOCUS_HOLD_MS && viewSize.width > 0) {
                val target = d.focusTarget
                if (target != null) {
                    if (auto.shouldRefocus(target, now)) {
                        val front = s.lens == Lens.FRONT
                        val cx = if (front) 1f - target.centerX else target.centerX
                        val regionSize = sqrt(target.area).coerceIn(0.1f, 0.4f)
                        val point = previewView.meteringPointFactory.createPoint(
                            cx * viewSize.width,
                            target.centerY * viewSize.height,
                            regionSize,
                        )
                        engine.focusOnSubject(point)
                        auto.focused(target, now)
                    }
                } else if (auto.subjectLost(now)) {
                    engine.cancelFocus()
                }
            }
            // En PRO la exposición es manual: la IA no la toca.
            if (s.mode != PhotoMode.PRO && d.exposureEv != auto.appliedEv) {
                engine.setExposureEv(d.exposureEv)
                auto.appliedEv = d.exposureEv
            }
        }
    }
    LaunchedEffect(iaEnabled) {
        if (!iaEnabled) {
            engine.cancelFocus()
            engine.setExposureEv(0f)
            auto.reset(auto.generation)
            direction = null
            guidance = null
        }
    }

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
                icon = Icons.Filled.BarChart,
                description = "Histograma",
                rotation = iconRotation,
                active = showHistogram,
                onClick = {
                    showHistogram = !showHistogram
                    prefs.histogram = showHistogram
                },
            )
            RotatingIconButton(
                icon = Icons.Filled.AutoAwesome,
                description = "IA automática",
                rotation = iconRotation,
                active = iaEnabled,
                onClick = {
                    iaEnabled = !iaEnabled
                    prefs.ia = iaEnabled
                },
            )
            RotatingIconButton(
                icon = Icons.Filled.CameraEnhance,
                description = "Disparo inteligente",
                rotation = iconRotation,
                active = smartShutter,
                enabled = iaEnabled,
                onClick = {
                    smartShutter = !smartShutter
                    prefs.smartShutter = smartShutter
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
                .aspectRatio(3f / 4f)
                .onSizeChanged { viewSize = it },
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
                            auto.lastManualFocusMs = SystemClock.elapsedRealtime()
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

            val currentPerception = perception
            val coachActive = iaEnabled && state.analysisActive && currentPerception != null && guidance != null
            // Con la IA activa, la rejilla es la guía que elige la IA (o la que fijes); sin IA, tercios.
            if (showGrid && !coachActive) GridOverlay()
            if (showLevel) LevelOverlay(levelState)
            if (iaEnabled && state.analysisActive && currentPerception != null) {
                SubjectOverlay(currentPerception, front = state.lens == Lens.FRONT)
                guidance?.let { g ->
                    CompositionOverlay(g, front = state.lens == Lens.FRONT, showGuide = showGrid)
                    ScoreBadge(
                        score = g.score,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                    )
                    GuideChip(
                        style = g.style,
                        forced = forcedGuide,
                        onSelect = { choice ->
                            forcedGuide = choice
                            prefs.guide = choice?.name
                            guideStabilizer.reset()
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 64.dp, end = 10.dp),
                    )
                }
                SceneBadge(
                    perception = currentPerception,
                    reason = direction?.reason,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp),
                )
                // Prioridad: consejo con acción > corrección del encuadre > "buen encuadre" > consejo de escena.
                val g = guidance
                val tipToShow = direction?.tip?.takeIf { it.action != null }
                    ?: g?.message?.let { Tip(it) }
                    ?: (if (g?.isGood == true) Tip("Buen encuadre: ¡dispara!") else null)
                    ?: direction?.tip
                tipToShow?.let { tip ->
                    TipChip(
                        tip = tip,
                        onAction = {
                            when (val action = tip.action) {
                                is TipAction.SwitchMode -> engine.setMode(action.mode)
                                is TipAction.SetZoom -> engine.setZoom(action.ratio)
                                null -> Unit
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 64.dp, start = 16.dp, end = 16.dp),
                    )
                }
            }
            if (showHistogram) {
                currentPerception?.light?.let { light ->
                    HistogramPanel(
                        light = light,
                        frameMs = currentPerception.frameMs,
                        timings = currentPerception.timings,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 100.dp, end = 10.dp),
                    )
                }
            }
            if (iaEnabled && state.ready && !state.analysisActive) {
                SmallNotice(
                    "IA en pausa en este modo",
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp),
                )
            }
            iaError?.let { error ->
                SmallNotice(
                    error,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 112.dp, start = 16.dp, end = 16.dp),
                )
            }
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
                if (smartShutter && iaEnabled) InfoChip("AUTO-DISPARO")
                val plan = CaptureDirector.plan(state.mode, perception, iaEnabled, state.flash == FlashSetting.ON)
                if (plan.strategy != CaptureStrategy.SINGLE && state.processing == null) InfoChip(plan.description)
            }

            // Foto computacional en curso: primero se capturan las fotos, luego se procesan.
            state.processing?.let { processing ->
                ProcessingOverlay(processing, Modifier.align(Alignment.Center))
            }
            if (state.capturing && state.processing == null && state.mode.vendor) {
                ProcessingOverlay(
                    Processing("Procesando", 0f, capturingFrames = true),
                    Modifier.align(Alignment.Center),
                )
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 112.dp, start = 16.dp, end = 16.dp)
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
            val pro = state.pro
            val proRanges = state.proRanges
            if (state.mode == PhotoMode.PRO && pro != null && proRanges != null) {
                ProPanel(
                    ranges = proRanges,
                    settings = pro,
                    onChange = { iso, exposure -> engine.setProExposure(iso, exposure) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
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
                ShutterButton(
                    capturing = state.capturing,
                    enabled = state.ready,
                    onClick = shoot,
                    smartShutter = smartShutter && iaEnabled,
                    autoProgress = autoProgress,
                )
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

private const val MANUAL_FOCUS_HOLD_MS = 6000L

/** Recuerda qué enfoque automático se ha aplicado para no repetirlo en cada fotograma. */
private class AutoFocusState {
    var generation = -1
    var lastManualFocusMs = 0L
    var appliedEv = Float.NaN
    private var lastTarget: NRect? = null
    private var lastFocusMs = 0L
    private var lostSinceMs = 0L

    fun reset(newGeneration: Int) {
        generation = newGeneration
        appliedEv = Float.NaN
        lastTarget = null
        lastFocusMs = 0L
        lostSinceMs = 0L
    }

    /** Solo se vuelve a enfocar si el sujeto es nuevo, se ha movido o ha cambiado de tamaño. */
    fun shouldRefocus(target: NRect, now: Long): Boolean {
        lostSinceMs = 0L
        val previous = lastTarget ?: return now - lastFocusMs > 400L
        val moved = hypot(target.centerX - previous.centerX, target.centerY - previous.centerY) > 0.12f
        val resized = abs(target.area - previous.area) > max(previous.area, 0.01f) * 0.6f
        return (moved || resized) && now - lastFocusMs > 1200L
    }

    fun focused(target: NRect, now: Long) {
        lastTarget = target
        lastFocusMs = now
    }

    /** true una sola vez cuando el sujeto lleva más de 1,5 s sin verse (vuelve el enfoque normal). */
    fun subjectLost(now: Long): Boolean {
        if (lastTarget == null) return false
        if (lostSinceMs == 0L) {
            lostSinceMs = now
            return false
        }
        if (now - lostSinceMs > 1500L) {
            lastTarget = null
            lostSinceMs = 0L
            return true
        }
        return false
    }
}

/** Disparo inteligente: dispara cuando todo está bien durante un momento, con pausa entre fotos. */
private class SmartShutterState {
    private var holdStartMs = 0L
    private var lastShotMs = 0L

    /** Progreso 0–1 de la espera; 1 = disparar ya. */
    fun update(ready: Boolean, now: Long): Float {
        if (!ready || now - lastShotMs < COOLDOWN_MS) {
            holdStartMs = 0L
            return 0f
        }
        if (holdStartMs == 0L) holdStartMs = now
        return ((now - holdStartMs) / HOLD_MS.toFloat()).coerceAtMost(1f)
    }

    fun fired(now: Long) {
        lastShotMs = now
        holdStartMs = 0L
    }

    fun reset() {
        holdStartMs = 0L
    }

    private companion object {
        const val HOLD_MS = 700L
        const val COOLDOWN_MS = 3000L
    }
}

/** Progreso de una foto computacional: "mantén quieto" mientras captura, porcentaje mientras procesa. */
@Composable
private fun ProcessingOverlay(processing: Processing, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(processing.label, color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (processing.capturingFrames) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Mantén el móvil quieto", color = Color.White, fontSize = 13.sp)
        } else {
            LinearProgressIndicator(
                progress = { processing.progress },
                color = Amber,
                modifier = Modifier.size(width = 160.dp, height = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("Procesando ${(processing.progress * 100).toInt()} %", color = Color.White, fontSize = 13.sp)
        }
    }
}
