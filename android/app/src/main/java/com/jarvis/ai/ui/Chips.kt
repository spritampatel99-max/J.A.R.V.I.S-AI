package com.jarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A small rounded glass chip, optionally with an icon. [selected] gives it the cyan tint used for active filters. */
@Composable
fun GlassChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Glyph? = null, selected: Boolean = false) {
    val tint = if (selected) JarvisColors.Cyan else JarvisColors.Muted
    Row(
        modifier
            .clip(CircleShape)
            .background(if (selected) JarvisColors.Cyan.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) JarvisColors.Cyan.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (glyph != null) GlyphIcon(glyph, tint, size = 16.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) JarvisColors.Cyan else JarvisColors.Ink)
    }
}
