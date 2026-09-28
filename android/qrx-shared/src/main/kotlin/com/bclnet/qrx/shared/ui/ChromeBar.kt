/*
 * ChromeBar.kt
 * QRX
 *
 * The overlay bar over the camera: settings/Bluetooth, the status message,
 * forget-glyphs and the torch (Android counterpart of ChromeView.swift).
 */
package com.bclnet.qrx.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bclnet.qrx.shared.GlyphSession

@Composable
fun ChromeBar(session: GlyphSession, onToggleFlash: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
        IconButton(onClick = { session.showSettings = true }) {
            Icon(Icons.Filled.AccountCircle, contentDescription = "Settings and Bluetooth", tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (session.found.isNotEmpty()) {
                Text("${session.found.size} glyph${if (session.found.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = { session.forgetGlyphs() }) {
            Icon(Icons.Filled.Refresh, contentDescription = "Forget glyphs", tint = MaterialTheme.colorScheme.onSurface)
        }
        if (onToggleFlash != null) {
            IconButton(onClick = onToggleFlash) {
                Icon(if (session.flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = "Torch", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
