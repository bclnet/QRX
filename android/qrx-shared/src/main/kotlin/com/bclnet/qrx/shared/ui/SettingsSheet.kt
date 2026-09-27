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
import com.bclnet.qrx.core.blue.LedColor
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
