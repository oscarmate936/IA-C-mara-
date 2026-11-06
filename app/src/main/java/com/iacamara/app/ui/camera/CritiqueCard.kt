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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.coach.Critique

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Color(0xFF7CD992)
    score >= 60 -> Color(0xFFFFC94D)
    else -> Color(0xFFFF8A80)
}

/**
 * Crítica del fotógrafo de la última foto. Plegada: nota, apartados y el consejo principal. Al tocarla
 * se despliega con todos los consejos y el porqué de la técnica usada. La ✕ la cierra.
 */
@Composable
fun CritiqueCard(critique: Critique, expanded: Boolean, onToggle: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Crítica", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("${critique.overall}", color = scoreColor(critique.overall), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                critique.parts.joinToString(" · ") { "${it.name} ${it.score}" },
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "✕",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(start = 4.dp),
            )
        }
        if (expanded) {
            critique.parts.forEach { part ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${part.score}", color = scoreColor(part.score), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${part.name}: ${part.note}", color = Color.White, fontSize = 12.sp)
                }
            }
            critique.best?.let { Text(it, color = Color(0xFF7CD992), fontSize = 12.sp) }
            critique.tips.forEach { Text("• $it", color = Color.White, fontSize = 12.sp) }
            Text(critique.technique, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        } else {
            (critique.tips.firstOrNull() ?: critique.best)?.let {
                Text(it, color = Color.White, fontSize = 12.sp, maxLines = 2)
            }
            Text("Toca para ver más", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }
}
