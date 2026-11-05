package com.iacamara.app.ui.camera

import android.net.Uri
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.ui.theme.Amber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Botón de icono que gira con el móvil para que siempre se lea derecho. */
@Composable
fun RotatingIconButton(
    icon: ImageVector,
    description: String,
    rotation: Float,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                active -> Amber
                else -> Color.White
            },
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
fun TextToggle(label: String, active: Boolean, rotation: Float, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .rotate(rotation)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Amber else Color.Transparent)
            .border(1.dp, if (active) Amber else Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Atajos de zoom (0.6x, 1x, 2x, 3x…) según lo que permita cada cámara. */
@Composable
fun ZoomBar(zoom: Float, minZoom: Float, maxZoom: Float, rotation: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    val presets = buildList {
        if (minZoom < 0.95f) add(minZoom)
        add(1f)
        if (maxZoom >= 2f) add(2f)
        if (maxZoom >= 3f) add(3f)
        if (maxZoom >= 10f) add(10f)
    }
    if (presets.size < 2 && maxZoom <= 1.01f) return
    val selected = presets.lastOrNull { zoom >= it * 0.98f } ?: presets.first()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { preset ->
            val isSelected = preset == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (isSelected) 40.dp else 34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = if (isSelected) 0.7f else 0.3f))
                    .clickable { onSelect(preset) },
            ) {
                Text(
                    text = if (isSelected) zoomLabel(zoom, withX = true) else zoomLabel(preset, withX = false),
                    color = if (isSelected) Amber else Color.White,
                    fontSize = if (isSelected) 12.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
}

private fun zoomLabel(value: Float, withX: Boolean): String {
    val text = if (abs(value - value.roundToInt()) < 0.05f) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return if (withX) "${text}x" else text
}

@Composable
fun ModeSelector(modes: List<PhotoMode>, selected: PhotoMode, onSelect: (PhotoMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        modes.forEach { mode ->
            val isSelected = mode == selected
            Text(
                text = mode.label,
                color = if (isSelected) Amber else Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun ShutterButton(capturing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (capturing) 0.88f else 1f, label = "shutterScale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled && !capturing, onClick = onClick),
    ) {
        if (capturing) CircularProgressIndicator(color = Amber, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

/** Miniatura de la última foto. */
@Composable
fun LastPhotoThumbnail(uri: Uri?, rotation: Float, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(it, Size(192, 192), null).asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(54.dp)
            .rotate(rotation)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            .clickable(enabled = uri != null, onClick = onClick),
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = "Última foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
