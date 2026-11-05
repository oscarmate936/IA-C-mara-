package com.iacamara.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.settings.AppPrefs
import com.iacamara.app.settings.EnhanceMode

/** Ajustes de la app: mejora automática y acceso al diagnóstico. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context.applicationContext) }
    var enhance by remember { mutableStateOf(prefs.enhance) }
    var geometry by remember { mutableStateOf(prefs.enhanceGeometry) }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Text("Ajustes", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Section("Mejora automática")
            Note(
                "Después de cada foto, la IA revela una copia con la receta de la escena (color, luz, " +
                    "contraste, piel, cielo y nitidez). Solo en los modos de la app; los del fabricante ya " +
                    "vienen procesados y en PRO mandas tú.",
            )
            EnhanceMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            enhance = mode
                            prefs.enhance = mode
                        }
                        .padding(vertical = 2.dp),
                ) {
                    RadioButton(selected = enhance == mode, onClick = {
                        enhance = mode
                        prefs.enhance = mode
                    })
                    Column {
                        Text(mode.label, color = Color.White, fontSize = 15.sp)
                        Text(mode.description, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Enderezar y recortar", color = Color.White, fontSize = 15.sp)
                    Text(
                        "En la copia mejorada: endereza con el giro medido al disparar, corrige la perspectiva " +
                            "de los edificios y recorta un poco para dejar el sujeto donde marcaba la guía.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = geometry,
                    enabled = enhance != EnhanceMode.NO,
                    onCheckedChange = {
                        geometry = it
                        prefs.enhanceGeometry = it
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Section("Diagnóstico")
            Note("Lo que este móvil permite a la app y dónde corre cada modelo de IA (NPU, GPU o procesador).")
            OutlinedButton(onClick = onOpenDiagnostics) { Text("Abrir diagnóstico de cámara") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Note(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
}
