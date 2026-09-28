/*
 * SettingsSheet.kt
 * QRX
 *
 * Bluetooth panel: the BLUE server, nearby QRX devices, the Particle LED
 * board (sliders and battery) and the found glyphs. Port of the iOS
 * SettingsView.
 */
package com.bclnet.qrx.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.bclnet.qrx.core.blue.LedColor
import com.bclnet.tokenx.ProviderKind
import com.bclnet.qrx.core.blue.ParticleUUIDs
import com.bclnet.qrx.shared.GlyphSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(session: GlyphSession, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SettingsContent(session, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp))
    }
}

@Composable
fun SettingsContent(session: GlyphSession, modifier: Modifier = Modifier) {
    val bluetooth = session.bluetooth
    Column(modifier) {
        AiSection(session)
        Section("Share glyphs over Bluetooth") {
            if (!bluetooth.isAvailable) Text("Bluetooth is not available on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("BLUE/1.0 server", Modifier.weight(1f))
                Switch(checked = bluetooth.serverEnabled, onCheckedChange = { bluetooth.enableServer(it) }, enabled = bluetooth.isAvailable)
            }
            OutlinedTextField(value = bluetooth.localName, onValueChange = { bluetooth.rename(it) }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Labeled("State", bluetooth.server.state)
            Text("Other QRX devices can show a code with blue://${bluetooth.localName}/glyph/<name>.", style = MaterialTheme.typography.bodySmall)
            bluetooth.glyphService.names.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        Section("Nearby QRX devices") {
            val client = bluetooth.client
            if (client.discovered.isEmpty()) Text(if (client.isScanning) "Scanning…" else "None found", style = MaterialTheme.typography.bodySmall)
            client.discovered.forEach { device ->
                Row(Modifier.fillMaxWidth()) {
                    Text(device.name, Modifier.weight(1f))
                    Text("${device.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedButton(onClick = { if (client.isScanning) client.stopScan() else client.startScan() }, enabled = bluetooth.isAvailable) {
                Text(if (client.isScanning) "Stop scanning" else "Scan")
            }
        }
        Section("Particle LED board") {
            val led = bluetooth.led
            Labeled("State", led.state)
            led.batteryLevel?.let { Labeled("Battery", "$it%") }
            var red by remember { mutableFloatStateOf(led.color.red.toFloat()) }
            var green by remember { mutableFloatStateOf(led.color.green.toFloat()) }
            var blue by remember { mutableFloatStateOf(led.color.blue.toFloat()) }
            val write = { led.write(LedColor.of(red.toDouble(), green.toDouble(), blue.toDouble())) }
            ChannelSlider("Red", red, { red = it }, write, led.isConnected)
            ChannelSlider("Green", green, { green = it }, write, led.isConnected)
            ChannelSlider("Blue", blue, { blue = it }, write, led.isConnected)
            Button(onClick = { red = 0f; green = 0f; blue = 0f; write() }, enabled = led.isConnected) { Text("Off") }
            Text("Service ${ParticleUUIDs.LED_SERVICE}", style = MaterialTheme.typography.labelSmall)
        }
        Section("Found glyphs") {
            if (session.found.isEmpty()) Text("None yet", style = MaterialTheme.typography.bodySmall)
            session.found.forEach { glyph ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(glyph.barcode.location ?: glyph.barcode.payload, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text(glyph.status, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    content()
    HorizontalDivider(Modifier.padding(top = 12.dp))
}

@Composable
private fun Labeled(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChannelSlider(label: String, value: Float, onChange: (Float) -> Unit, onFinished: () -> Boolean, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp))
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = { onFinished() }, valueRange = 0f..255f, steps = 254, enabled = enabled, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value.toInt().toString(), Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** TokenX: the provider the actors' minds use, its key, and today's usage. Keys are stored encrypted. */
@Composable
private fun AiSection(session: GlyphSession) {
    val ai = session.ai
    var provider by remember { mutableStateOf(ai.settings.activeProvider ?: ProviderKind.ANTHROPIC) }
    var key by remember { mutableStateOf("") }
    var localUrl by remember { mutableStateOf(ai.settings.localBaseUrl ?: "") }
    var localModel by remember { mutableStateOf(ai.settings.localModel ?: "") }
    var menu by remember { mutableStateOf(false) }
    Section("AI (TokenX)") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Provider", Modifier.weight(1f))
            TextButton(onClick = { menu = true }) { Text(provider.displayName + if (provider in ai.configured) " ✓" else "") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (kind in ProviderKind.entries) DropdownMenuItem(text = { Text(kind.displayName + if (kind in ai.configured) " ✓" else "") }, onClick = { provider = kind; menu = false })
            }
        }
        if (provider.needsKey) {
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(if (provider in ai.configured) "API key (stored; enter to replace)" else "API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(value = localUrl, onValueChange = { localUrl = it }, label = { Text("Server URL, e.g. http://192.168.1.20:11434/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = localModel, onValueChange = { localModel = it }, label = { Text("Model name, e.g. llama3") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = {
                if (!provider.needsKey) ai.update { it.copy(localBaseUrl = localUrl.ifBlank { null }, localModel = localModel.ifBlank { null }) }
                ai.activate(provider, key)
                key = ""
            }, enabled = !(provider.needsKey && key.isBlank() && provider !in ai.configured)) {
                Text(if (ai.settings.activeProvider == provider && key.isBlank()) "Active" else "Use ${provider.displayName}")
            }
            if (provider in ai.configured) OutlinedButton(onClick = { ai.removeKey(provider) }, modifier = Modifier.padding(start = 8.dp)) { Text("Remove key") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep prompts in the usage log", Modifier.weight(1f))
            Switch(checked = ai.settings.logPrompts, onCheckedChange = { on -> ai.update { it.copy(logPrompts = on) } })
        }
        Labeled("Today", "%d requests, %d tokens, $%.4f".format(ai.usageToday.requests, ai.usageToday.totalTokens, ai.usageToday.costUsd))
        Text(ai.characterModel?.let { "Characters answer with $it." } ?: "Pick a provider and enter its API key; until then actors use their canned rules.", style = MaterialTheme.typography.bodySmall)
        ai.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
