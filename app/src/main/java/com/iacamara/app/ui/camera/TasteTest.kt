package com.iacamara.app.ui.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.iacamara.app.camera.EnhancedShot
import com.iacamara.app.capture.loadSmallUpright
import com.iacamara.app.imaging.AutoEnhance
import com.iacamara.app.imaging.ShotContext
import com.iacamara.app.imaging.StyleAdjust
import com.iacamara.app.learning.Dimension
import com.iacamara.app.settings.LearningBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Una versión de la prueba: dirección (−1, 0, +1) e imagen revelada con ese estilo. */
private class TasteOption(val dir: Int, val image: ImageBitmap)

/** Píxeles de la foto original reducida (una sola vez para toda la prueba). */
private class SmallPhoto(val pixels: IntArray, val width: Int, val height: Int)

/**
 * "Afinar mi estilo" (Fase 7): como en el óptico. Con la foto que acabas de hacer se revelan tres versiones
 * de un ajuste (un paso menos, igual y un paso más, en orden aleatorio) y eliges la que más te gusta. Son
 * [ROUNDS] rondas, siempre sobre el ajuste del que menos se sabe. Lo que eliges cambia tu estilo para ese
 * tipo de foto en cuanto una opción gana con claridad.
 *
 * @param onDone al terminar, con lo aprendido (para avisarlo) o null.
 */
@Composable
fun TasteTestDialog(shot: EnhancedShot, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val learning = remember { LearningBook.get(context) }
    val group = shot.group
    var round by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    var learned by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    val photo by produceState<SmallPhoto?>(null, shot.original) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val small = loadSmallUpright(context, shot.original, PREVIEW_SIDE) ?: return@runCatching null
                try {
                    val px = IntArray(small.width * small.height)
                    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                    SmallPhoto(px, small.width, small.height)
                } finally {
                    small.recycle()
                }
            }.getOrNull()
        }
        loadFailed = value == null
    }
    // El ajuste de esta ronda y sus versiones reveladas (en orden aleatorio, para no favorecer una posición).
    var dimension by remember { mutableStateOf<Dimension?>(null) }
    var options by remember { mutableStateOf<List<TasteOption>>(emptyList()) }
    LaunchedEffect(photo, round) {
        val p = photo ?: return@LaunchedEffect
        options = emptyList()
        selected = 0
        val dim = learning.style.tasteDimension(group)
        dimension = dim
        val styles = learning.style.tasteOptions(group, dim).shuffled()
        options = withContext(Dispatchers.Default) {
            styles.map { (dir, style) -> TasteOption(dir, render(p, shot, style)) }
        }
    }

    fun finish() {
        learning.save()
        onDone(learned)
    }

    fun choose(dir: Int?) {
        val dim = dimension ?: return
        learning.style.tasteVote(group, dim, dir)?.let {
            learning.note(it.text)
            learned = it.text
        }
        learning.save()
        if (round + 1 >= ROUNDS) finish() else round++
    }

    Dialog(onDismissRequest = { finish() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Afinar mi estilo · ${group.label}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Ronda ${round + 1} de $ROUNDS · ${dimension?.label ?: ""}: ¿cuál te gusta más?",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                modifier = Modifier.padding(4.dp),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val current = options.getOrNull(selected)
                when {
                    current != null ->
                        Image(current.image, contentDescription = "Versión ${selected + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    loadFailed -> Text("No se pudo abrir la foto original (¿la has borrado?)", color = Color.White, fontSize = 14.sp)
                    else -> CircularProgressIndicator(color = Color.White)
                }
            }
            learned?.let { Text(it, color = Color(0xFF7CD992), fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
            Text(
                "Toca 1, 2 y 3 para cambiar de versión y compararlas",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                options.forEachIndexed { i, _ ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (i == selected) Color.White else Color.White.copy(alpha = 0.15f))
                            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            .clickable { selected = i },
                    ) {
                        Text("${i + 1}", color = if (i == selected) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                Pill("Me quedo con la ${selected + 1}", enabled = options.isNotEmpty()) { options.getOrNull(selected)?.let { choose(it.dir) } }
                Pill("Me da igual", enabled = options.isNotEmpty()) { choose(null) }
            }
            Text(
                "Terminar",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.clickable { finish() }.padding(12.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** La foto revelada con la receta de su escena y el estilo indicado (como la copia mejorada, pero pequeña). */
private fun render(p: SmallPhoto, shot: EnhancedShot, style: StyleAdjust): ImageBitmap {
    val ctx = ShotContext(shot.scene, alreadyProcessed = shot.alreadyProcessed)
    val out = AutoEnhance.enhance(p.pixels, p.width, p.height, ctx, style = style)
    return Bitmap.createBitmap(out, p.width, p.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val ROUNDS = 8
private const val PREVIEW_SIDE = 1200
