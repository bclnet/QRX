/*
 * MainActivity.kt
 * QRX (Android phone app)
 *
 * Camera preview with glyph cards anchored on the QR codes in view, the
 * chrome bar and the Bluetooth settings sheet.
 */
package com.bclnet.qrx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bclnet.jsonscene.compose.JsonScene
import com.bclnet.qrx.shared.GlyphSession
import com.bclnet.qrx.shared.blue.BluePermissions
import com.bclnet.qrx.shared.blue.BluetoothService
import com.bclnet.qrx.shared.scan.GlyphScanner
import com.bclnet.qrx.shared.ui.ChromeBar
import com.bclnet.qrx.shared.ui.GlyphOverlay
import com.bclnet.qrx.shared.ui.SettingsSheet

class MainActivity : ComponentActivity() {
    private val bluetooth by lazy { BluetoothService(this) }
    private val session by lazy { GlyphSession(this, bluetooth) }
    private val scanner by lazy { GlyphScanner(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JsonScene.register()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                QrxScreen(session, scanner, bluetooth)
            }
        }
    }

    override fun onDestroy() {
        bluetooth.stop()
        super.onDestroy()
    }
}

@Composable
fun QrxScreen(session: GlyphSession, scanner: GlyphScanner, bluetooth: BluetoothService) {
    val required = remember { BluePermissions.required() }
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        granted = results[BluePermissions.CAMERA] == true
        if (results.filterKeys { it != BluePermissions.CAMERA }.values.all { it }) bluetooth.start()
    }
    LaunchedEffect(Unit) { launcher.launch(required.toTypedArray()) }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            GlyphOverlay(session, scanner)
        } else {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Button(onClick = { launcher.launch(required.toTypedArray()) }) { Text("Allow camera access to scan glyphs") }
            }
        }
        ChromeBar(session, modifier = Modifier.align(Alignment.TopCenter))
        if (session.showSettings) SettingsSheet(session) { session.showSettings = false }
    }
}
