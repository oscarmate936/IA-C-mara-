package com.iacamara.app.ui.camera

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "¿Te gusta la mejora?" después de cada foto mejorada (Fase 7): 👍/👎 enseñan tu estilo a la app.
 * Si se guardaron las dos versiones, "Comparar" las enseña a pantalla completa.
 */
@Composable
fun EnhanceCard(
    shot: EnhancedShot,
    onRate: (Boolean) -> Unit,
    onCompare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("Mejora lista: ¿te gusta?", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (shot.bothKept) {
            Text(
                "Comparar",
                color = Color(0xFFFFC94D),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onCompare).padding(4.dp),
            )
        }
        Text("👍", fontSize = 18.sp, modifier = Modifier.clickable { onRate(true) }.padding(4.dp))
        Text("👎", fontSize = 18.sp, modifier = Modifier.clickable { onRate(false) }.padding(4.dp))
        Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClose).padding(4.dp))
    }
}

/**
 * La copia mejorada a pantalla completa; manteniendo pulsado se ve la original. Desde aquí se puede
 * afinar tu estilo con esta misma foto.
 */
@Composable
fun CompareDialog(shot: EnhancedShot, onRate: (Boolean) -> Unit, onTaste: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val after by produceState<ImageBitmap?>(null, shot.enhanced) { value = load(context, shot.enhanced) }
    val before by produceState<ImageBitmap?>(null, shot.original) { value = load(context, shot.original) }
    var showOriginal by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                showOriginal = true
                                tryAwaitRelease()
                                showOriginal = false
                            },
                        )
                    },
            ) {
                val image = if (showOriginal) before else after
                if (image != null) {
                    Image(bitmap = image, contentDescription = if (showOriginal) "Original" else "Mejorada", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
                Text(
                    if (showOriginal) "Original" else "Mejorada",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                "Mantén pulsada la foto para ver la original",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                ChoiceButton("👎 Prefiero la original") { onRate(false) }
                ChoiceButton("👍 Me gusta la mejorada") { onRate(true) }
            }
            Text(
                "🎯 Afinar mi estilo (8 comparaciones rápidas)",
                color = Color(0xFFFFC94D),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onTaste).padding(8.dp),
            )
            Text(
                "Cerrar",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(bottom = 20.dp, top = 4.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun ChoiceButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

private suspend fun load(context: android.content.Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { loadSmallUpright(context, uri, COMPARE_SIDE)?.asImageBitmap() }.getOrNull()
}

private const val COMPARE_SIDE = 1600
