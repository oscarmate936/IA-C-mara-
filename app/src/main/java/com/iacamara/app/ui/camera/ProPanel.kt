package com.iacamara.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.camera.ProRanges
import com.iacamara.app.camera.ProSettings
import com.iacamara.app.ui.theme.Amber
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Velocidad más lenta que se ofrece a pulso en el modo PRO. */
private const val MAX_HANDHELD_NS = 1_000_000_000L

/**
 * Modo PRO: ISO y velocidad de obturación manuales (escala logarítmica, como en las cámaras),
 * y cuánto difiere la exposición elegida de la que había decidido la IA.
 */
@Composable
fun ProPanel(ranges: ProRanges, settings: ProSettings, onChange: (Int, Long) -> Unit, modifier: Modifier = Modifier) {
    val maxExposure = minOf(ranges.exposureMaxNs, MAX_HANDHELD_NS)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        ProSlider(
            label = "ISO ${settings.iso}",
            position = logPosition(settings.iso.toDouble(), ranges.isoMin.toDouble(), ranges.isoMax.toDouble()),
            onChange = { t -> onChange(fromLog(t, ranges.isoMin.toDouble(), ranges.isoMax.toDouble()).roundToInt(), settings.exposureNs) },
        )
        ProSlider(
            label = shutterText(settings.exposureNs),
            position = logPosition(settings.exposureNs.toDouble(), ranges.exposureMinNs.toDouble(), maxExposure.toDouble()),
            onChange = { t -> onChange(settings.iso, fromLog(t, ranges.exposureMinNs.toDouble(), maxExposure.toDouble()).toLong()) },
        )
        settings.referenceEv?.let { reference ->
            // EV más bajo = foto más clara. Diferencia respecto a lo que medía la IA.
            val seconds = settings.exposureNs / 1e9
            val ev = log2(settings.aperture.toDouble() * settings.aperture / seconds) - log2(settings.iso / 100.0)
            val diff = reference - ev
            val text = when {
                abs(diff) < 0.2 -> "Exposición igual que la IA"
                diff > 0 -> String.format(Locale.US, "%.1f EV más clara que la IA", diff).replace('.', ',')
                else -> String.format(Locale.US, "%.1f EV más oscura que la IA", -diff).replace('.', ',')
            }
            Text(text, color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ProSlider(label: String, position: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(78.dp))
        Slider(
            value = position,
            onValueChange = onChange,
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun logPosition(value: Double, min: Double, max: Double): Float {
    if (max <= min) return 0f
    return (ln(value / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)
}

private fun fromLog(t: Float, min: Double, max: Double): Double = min * (max / min).pow(t.toDouble())

fun shutterText(ns: Long): String {
    val seconds = ns / 1e9
    return if (seconds >= 0.4) String.format(Locale.US, "%.1f s", seconds).replace('.', ',') else "1/${(1 / seconds).roundToInt()} s"
}
