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
import androidx.compose.runtime.mutableIntStateOf
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
import com.iacamara.app.settings.HabitBook
import com.iacamara.app.settings.LearningBook
import com.iacamara.app.learning.TipLearner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes de la app: mejora automática, fotógrafo veterano (crítica y hábitos), tu estilo, RAW y diagnóstico. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context.applicationContext) }
    var enhance by remember { mutableStateOf(prefs.enhance) }
    var geometry by remember { mutableStateOf(prefs.enhanceGeometry) }
    var raw by remember { mutableStateOf(prefs.raw) }
    var critique by remember { mutableStateOf(prefs.critique) }
    val habitBook = remember { HabitBook.get(context.applicationContext) }
    var habits by remember { mutableStateOf(habitBook.active()) }
    val learning = remember { LearningBook.get(context.applicationContext) }
    var learn by remember { mutableStateOf(prefs.learning) }
    var muted by remember { mutableStateOf(learning.tips.mutedList(System.currentTimeMillis())) }
    // Cambia al olvidar lo aprendido, para que la sección se vuelva a dibujar vacía.
    var forgotten by remember { mutableIntStateOf(0) }
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.forLanguageTag("es-ES")) }
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
            Section("Fotógrafo veterano")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Crítica después de cada foto", color = Color.White, fontSize = 15.sp)
                    Text(
                        "Nota por apartados (composición, luz, nitidez, momento y color), lo mejor de la foto, " +
                            "2–3 consejos para la próxima vez y por qué se usó esa técnica.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = critique, onCheckedChange = {
                    critique = it
                    prefs.critique = it
                })
            }
            Spacer(Modifier.height(4.dp))
            Text("Tus hábitos", color = Color.White, fontSize = 15.sp)
            Note(
                "La app recuerda, solo en este móvil, los fallos de tus últimas fotos. Cuando uno se repite " +
                    "(4 de las últimas 10) te avisa justo cuando vuelve a pasar y, si puede, lo compensa: " +
                    "con fotos movidas hace 4 y elige la más nítida.",
            )
            if (habits.isEmpty()) {
                Text("Ningún fallo se repite por ahora.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            } else {
                habits.forEach { h ->
                    Text("• ${h.habit.label}: ${h.text}", color = Color.White, fontSize = 13.sp)
                }
            }
            OutlinedButton(onClick = {
                habitBook.clear()
                habits = emptyList()
            }) { Text("Olvidar mis hábitos") }

            Spacer(Modifier.height(12.dp))
            Section("Tu estilo")
            Note(
                "La app aprende cómo te gusta la mejora automática en cada tipo de foto, solo en este móvil (y " +
                    "lo conserva al actualizarla). Lo más rápido: después de una foto, toca «Comparar» → «Afinar mi " +
                    "estilo» y elige entre tres versiones, como en el óptico (8 rondas). También aprende, poco a " +
                    "poco, de tus 👍/👎, de qué copia borras (si guardas las dos), de los consejos que silencias " +
                    "y de lo que ya dominas.",
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Aprender de mis fotos", color = Color.White, fontSize = 15.sp)
                    Text(
                        "Si lo desactivas deja de probar versiones y de preguntar; lo ya aprendido se sigue aplicando.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = learn, onCheckedChange = {
                    learn = it
                    prefs.learning = it
                })
            }
            val styles = remember(forgotten) { learning.style.summary() }
            if (styles.isEmpty()) {
                Text("Todavía no ha aprendido nada: afina tu estilo o valora algunas fotos mejoradas.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            } else {
                styles.forEach { (group, g) ->
                    val look = g.style.describe().ifEmpty { "como la receta de la escena" }
                    Text("• ${group.label}: $look (${g.choices} elecciones, ${g.ratings} valoraciones)", color = Color.White, fontSize = 13.sp)
                }
            }
            val mastered = remember(forgotten) { learning.tips.mastered() }
            if (mastered.isNotEmpty()) {
                Text(
                    "Ya dominas: " + mastered.sorted().joinToString(", ") { TipLearner.skillLabel(it) } +
                        ". Esos consejos salen menos.",
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
            if (muted.isNotEmpty()) {
                Text("Consejos silenciados (30 días):", color = Color.White, fontSize = 13.sp)
                muted.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("«${m.text}»", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "Volver a mostrar",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable {
                                    learning.tips.unmute(m.id)
                                    muted = learning.tips.mutedList(System.currentTimeMillis())
                                }
                                .padding(8.dp),
                        )
                    }
                }
            }
            val events = remember(forgotten) { learning.log.recent(5) }
            if (events.isNotEmpty()) {
                Text("Últimos aprendizajes:", color = Color.White, fontSize = 13.sp)
                events.forEach { e ->
                    Text("• ${dateFormat.format(Date(e.time))} · ${e.text}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = {
                learning.forget()
                muted = emptyList()
                forgotten++
            }) { Text("Olvidar lo aprendido") }

            Spacer(Modifier.height(12.dp))
            Section("Modo PRO")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Guardar también RAW (DNG)", color = Color.White, fontSize = 15.sp)
                    Text(
                        "El archivo del sensor sin procesar, para revelarlo después (Lightroom, Snapseed…). " +
                            "Ocupa unos 20–25 MB por foto. Solo si el móvil lo permite.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = raw, onCheckedChange = {
                    raw = it
                    prefs.raw = it
                })
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
