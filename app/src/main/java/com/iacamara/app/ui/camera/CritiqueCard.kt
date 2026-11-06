package com.iacamara.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.coach.Critique

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Color(0xFF7CD992)
    score >= 60 -> Color(0xFFFFC94D)
    else -> Color(0xFFFF8A80)
}

/** Lo que se puede hacer con la copia mejorada desde la tarjeta de la última foto. */
class EnhanceActions(val onRate: (Boolean) -> Unit, val onCompare: (() -> Unit)?, val onClose: () -> Unit)

/**
 * Crítica del fotógrafo de la última foto, para tapar el visor lo menos posible. Plegada es una sola línea
 * (nota y el consejo principal) y, si hay copia mejorada, otra con 👍/👎. Al tocarla se despliega con todos
 * los apartados, los consejos y el porqué de la técnica. La ✕ la cierra.
 */
@Composable
fun CritiqueCard(
    critique: Critique,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enhance: EnhanceActions? = null,
) {
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${critique.overall}", color = scoreColor(critique.overall), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val headline = if (expanded) {
                critique.parts.joinToString(" · ") { "${it.name} ${it.score}" }
            } else {
                critique.tips.firstOrNull() ?: critique.best ?: "Toca para ver la crítica"
            }
            Text(
                headline,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                maxLines = if (expanded) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▾" else "›", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Text(
                "✕",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(start = 2.dp),
            )
        }
        if (expanded) {
            critique.parts.forEach { part ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${part.score}", color = scoreColor(part.score), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("${part.name}: ${part.note}", color = Color.White, fontSize = 11.sp)
                }
            }
            critique.best?.let { Text(it, color = Color(0xFF7CD992), fontSize = 11.sp) }
            critique.tips.forEach { Text("• $it", color = Color.White, fontSize = 11.sp) }
            Text(critique.technique, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
        enhance?.let { EnhanceRow(it) }
    }
}

/** "¿Te gusta la mejora?" en una línea: 👍, 👎, Comparar y ✕. */
@Composable
fun EnhanceRow(actions: EnhanceActions, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Text("¿Te gusta la mejora?", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
        actions.onCompare?.let { compare ->
            Text(
                "Comparar",
                color = Color(0xFFFFC94D),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = compare).padding(2.dp),
            )
        }
        Text("👍", fontSize = 15.sp, modifier = Modifier.clickable { actions.onRate(true) }.padding(2.dp))
        Text("👎", fontSize = 15.sp, modifier = Modifier.clickable { actions.onRate(false) }.padding(2.dp))
        Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.clickable(onClick = actions.onClose).padding(2.dp))
    }
}
