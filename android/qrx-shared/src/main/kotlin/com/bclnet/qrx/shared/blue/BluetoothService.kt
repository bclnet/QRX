/*
 * BluetoothService.kt
 * QRX
 *
 * Facade over the two Bluetooth roles: the BLUE/1.0 client (BlueGattClient)
 * and the BLUE/1.0 server (BlueGattServer). Android counterpart of the iOS
 * BluetoothService.
 */
package com.bclnet.qrx.shared.blue

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bclnet.qrx.core.blue.BlueGlyphService
import com.bclnet.qrx.core.blue.BlueUUIDs

class BluetoothService(private val context: Context) {
    private val prefs = context.getSharedPreferences("qrx.blue", Context.MODE_PRIVATE)
    val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val glyphService = BlueGlyphService()
    val client = BlueGattClient(context, adapter)
    val server = BlueGattServer(context, adapter, glyphService.router)

    var serverEnabled: Boolean by mutableStateOf(prefs.getBoolean("server", false))
        private set

    var localName: String by mutableStateOf(prefs.getString("name", null) ?: defaultName())
        private set

    init {
        server.localName = localName
    }

    /** Whether the adapter exists and is on (Quest headsets may expose no adapter to apps). */
    val isAvailable: Boolean get() = adapter?.isEnabled == true

    /** The permission is checked first and a SecurityException is caught by runCatching. */
    @SuppressLint("MissingPermission")
    fun defaultName(): String = runCatching { if (BluePermissions.hasConnect(context)) adapter?.name else null }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: (Build.MODEL.takeIf { it.isNotBlank() } ?: BlueUUIDs.DEFAULT_LOCAL_NAME)

    fun rename(name: String) {
        localName = name
        prefs.edit().putString("name", name).apply()
        server.localName = name
    }

    fun enableServer(enabled: Boolean) {
        serverEnabled = enabled
        prefs.edit().putBoolean("server", enabled).apply()
        if (enabled) server.start() else server.stop()
    }

    /** Call once permissions are granted. */
    fun start() {
        if (!isAvailable) return
        if (serverEnabled) server.start()
    }

    fun stop() {
        server.stop()
        client.close()
    }

    val isAnythingConnected: Boolean get() = server.subscriberCount > 0 || client.isConnected
}
