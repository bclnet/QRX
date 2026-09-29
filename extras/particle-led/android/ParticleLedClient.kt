/*
 * ParticleLedClient.kt
 * QRX
 *
 * Client for the Particle LED example board, a BLE peripheral: scans for
 * the LED service, connects, writes the
 * three colour characteristics and subscribes to the battery level.
 */
package com.bclnet.qrx.shared.blue

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bclnet.qrx.core.blue.BlueUUIDs
import com.bclnet.qrx.core.blue.LedColor
import com.bclnet.qrx.core.blue.ParticleUUIDs
import java.util.ArrayDeque

@SuppressLint("MissingPermission")
class ParticleLedClient(private val context: Context, private val adapter: BluetoothAdapter?) {
    var state: String by mutableStateOf("idle")
        private set
    var isConnected: Boolean by mutableStateOf(false)
        private set
    var batteryLevel: Int? by mutableStateOf(null)
        private set
    var color: LedColor by mutableStateOf(LedColor.OFF)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val characteristics = HashMap<LedColor.Channel, BluetoothGattCharacteristic>()
    private val writes = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writing = false
    private var wantsScan = false
    private var scanning = false

    /** Starts scanning for a board; reconnects when the board goes away. */
    fun start() {
        wantsScan = true
        scan()
    }

    fun stop() {
        wantsScan = false
        stopScan()
        gatt?.close()
        gatt = null
        isConnected = false
        state = "idle"
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { state = "no Bluetooth adapter"; return }
        if (adapter.isEnabled != true) { state = "Bluetooth is off"; return }
        if (!BluePermissions.hasScan(context)) { state = "scan permission missing"; return }
        if (scanning) return
        scanning = true
        state = "scanning for LED board"
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(ParticleUUIDs.LED_SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback)
    }

    private fun stopScan() {
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    /** Writes the colour; false when no board is connected. */
    fun write(color: LedColor): Boolean {
        this.color = color
        val gatt = gatt ?: return false
        if (!isConnected) return false
        synchronized(writes) {
            for (channel in LedColor.Channel.entries) {
                characteristics[channel]?.let { writes.add(it to color.bytes(channel)) }
            }
        }
        main.post { writeNext(gatt) }
        return characteristics.size == LedColor.Channel.entries.size
    }

    private fun writeNext(gatt: BluetoothGatt) {
        if (writing) return
        val (characteristic, bytes) = synchronized(writes) { writes.pollFirst() } ?: return
        writing = true
        characteristic.writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = bytes
        if (!gatt.writeCharacteristic(characteristic)) writing = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                stopScan()
                val device: BluetoothDevice = result.device
                state = "connecting to ${device.name ?: "board"}"
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) { main.post { scanning = false; state = "scan failed ($errorCode)" } }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state = "connected to ${gatt.device.name ?: "board"}"
                    gatt.discoverServices()
                } else {
                    isConnected = false
                    characteristics.clear()
                    batteryLevel = null
                    gatt.close()
                    this@ParticleLedClient.gatt = null
                    if (wantsScan) scan() else state = "disconnected"
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                gatt.getService(ParticleUUIDs.LED_SERVICE)?.let { service ->
                    for (channel in LedColor.Channel.entries) service.getCharacteristic(channel.characteristic)?.let { characteristics[channel] = it }
                }
                gatt.getService(ParticleUUIDs.BATTERY_SERVICE)?.getCharacteristic(ParticleUUIDs.BATTERY_LEVEL)?.let { battery ->
                    gatt.setCharacteristicNotification(battery, true)
                    battery.getDescriptor(BlueUUIDs.CCCD)?.let { descriptor ->
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    gatt.readCharacteristic(battery)
                }
                if (characteristics.size == LedColor.Channel.entries.size) {
                    isConnected = true
                    state = "ready (${gatt.device.name ?: "board"})"
                    write(color)
                } else {
                    state = "board lacks the LED characteristics"
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post { writing = false; writeNext(gatt) }
        }

        @Deprecated("Deprecated in API 33; the byte-array overload forwards here on older devices")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { battery(characteristic, it) }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) { battery(characteristic, value) }

        @Deprecated("Deprecated in API 33; the byte-array overload forwards here on older devices")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { battery(characteristic, it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { battery(characteristic, value) }
    }

    private fun battery(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == ParticleUUIDs.BATTERY_LEVEL && value.isNotEmpty()) main.post { batteryLevel = value[0].toInt() and 0xff }
    }
}
