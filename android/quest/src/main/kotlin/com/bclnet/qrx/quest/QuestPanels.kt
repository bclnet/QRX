/*
 * QuestPanels.kt
 * QRX (Meta Quest)
 *
 * The composables shown in Spatial SDK panels: the control panel (status,
 * found glyphs, Bluetooth settings) and a glyph slot panel (one glyph card).
 */
package com.bclnet.qrx.quest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bclnet.qrx.shared.ui.GlyphCard
import com.bclnet.qrx.shared.ui.SettingsContent

@Composable
fun QuestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) { content() }
    }
}

@Composable
fun ControlPanel(activity: QuestActivity) {
    val session = activity.session
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("QRX", style = MaterialTheme.typography.headlineMedium)
        Text(session.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        Text("${activity.trackerState} · ${activity.cameraState}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${session.found.size} glyph${if (session.found.size == 1) "" else "s"}, ${activity.slots.size} placed", Modifier.weight(1f))
            Button(onClick = { activity.clearSlots() }) { Text("Forget glyphs") }
        }
        HorizontalDivider()
        SettingsContent(session)
    }
}

@Composable
fun GlyphSlotPanel(activity: QuestActivity, slot: Int) {
    val payload = activity.slots[slot]
    val glyph = payload?.let { activity.session.glyph(it) }
    val speech = activity.speech[slot]
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        if (glyph != null) GlyphCard(glyph, activity.session, Modifier.fillMaxSize())
        else Text("Waiting for a glyph…", style = MaterialTheme.typography.bodySmall)
        if (speech != null) {
            Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp) {
                Text(speech, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
