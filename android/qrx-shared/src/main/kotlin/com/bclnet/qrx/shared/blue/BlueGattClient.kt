/*
 * BlueGattClient.kt
 * QRX
 *
 * BLUE/1.0 client over Android Bluetooth LE: scans for QRX peripherals,
 * connects to the one named in a `blue://` code (or any), writes the
 * request chunks and assembles the response from notifications. Implements
 * BlueTransport for GlyphLookup.
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bclnet.qrx.core.BlueTransport
import com.bclnet.qrx.core.blue.BlueAssembler
import com.bclnet.qrx.core.blue.BlueFramer
import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueResponse
import com.bclnet.qrx.core.blue.BlueUUIDs
import java.util.ArrayDeque

class BlueClientException(message: String) : Exception(message)

@SuppressLint("MissingPermission")
class BlueGattClient(private val context: Context, private val adapter: BluetoothAdapter?) : BlueTransport {
    data class Device(val address: String, val name: String, val rssi: Int)

    val discovered = mutableStateListOf<Device>()
    var isScanning: Boolean by mutableStateOf(false)
        private set
    var isConnected: Boolean by mutableStateOf(false)
        private set
    var state: String by mutableStateOf("idle")
        private set
    var timeoutMs: Long = 15_000

    private val main = Handler(Looper.getMainLooper())
    private val devices = HashMap<String, BluetoothDevice>()
    private var gatt: BluetoothGatt? = null
    private var request: BluetoothGattCharacteristic? = null
    private var response: BluetoothGattCharacteristic? = null
    private var mtu = BlueFramer.DEFAULT_MTU
    private val assembler = BlueAssembler()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private class Pending(val request: BlueRequest, val device: String, val callback: (Result<BlueResponse>) -> Unit, val timeout: Runnable)
    private var pending: Pending? = null
    private var connectTarget: String? = null

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { state = "no Bluetooth adapter"; return }
        if (!BluePermissions.hasScan(context)) { state = "scan permission missing"; return }
        discovered.clear()
        isScanning = true
        state = "scanning"
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BlueUUIDs.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        if (isScanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        if (state == "scanning") state = "idle"
    }

    fun close() {
        stopScan()
        gatt?.close()
        gatt = null
        isConnected = false
    }

    // MARK: - BlueTransport

    override fun send(request: BlueRequest, device: String, callback: (Result<BlueResponse>) -> Unit) {
        main.post { perform(request, device, callback) }
    }

    private fun perform(request: BlueRequest, device: String, callback: (Result<BlueResponse>) -> Unit) {
        if (pending != null) { callback(Result.failure(BlueClientException("a request is already in progress"))); return }
        if (adapter?.isEnabled != true) { callback(Result.failure(BlueClientException("Bluetooth is off"))); return }
        val timeout = Runnable { fail(BlueClientException("the device did not answer")) }
        pending = Pending(request, device, callback, timeout)
        main.postDelayed(timeout, timeoutMs)
        val gatt = gatt
        if (gatt != null && isConnected && this.request != null && matches(gatt.device, device)) {
            write(request)
        } else {
            connectTarget = device
            state = "looking for $device"
            val known = devices.values.firstOrNull { matches(it, device) }
            if (known != null) connect(known) else startScan()
        }
    }

    private fun matches(device: BluetoothDevice, name: String): Boolean =
        name == "*" || device.name?.equals(name, ignoreCase = true) == true || discovered.firstOrNull { it.address == device.address }?.name?.equals(name, ignoreCase = true) == true

    private fun connect(device: BluetoothDevice) {
        stopScan()
        gatt?.close()
        state = "connecting to ${device.name ?: device.address}"
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun write(request: BlueRequest) {
        val gatt = gatt ?: return fail(BlueClientException("not connected"))
        val characteristic = this.request ?: return fail(BlueClientException("the device does not expose the QRX service"))
        assembler.reset()
        state = "sending ${request.method} ${request.uri}"
        characteristic.writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        synchronized(writeQueue) { writeQueue.clear(); BlueFramer.frames(request.text, mtu).forEach { writeQueue.add(it) } }
        writing = false
        writeNext(gatt, characteristic)
    }

    private fun writeNext(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (writing) return
        val chunk = synchronized(writeQueue) { writeQueue.pollFirst() } ?: return
        writing = true
        characteristic.value = chunk
        if (!gatt.writeCharacteristic(characteristic)) { writing = false; fail(BlueClientException("write failed")) }
    }

    private fun finish(result: Result<BlueResponse>) {
        val p = pending ?: return
        main.removeCallbacks(p.timeout)
        pending = null
        state = "idle"
        p.callback(result)
    }

    private fun fail(error: Exception) = finish(Result.failure(error))

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "QRX"
            main.post {
                devices[device.address] = device
                val index = discovered.indexOfFirst { it.address == device.address }
                if (index >= 0) discovered[index] = Device(device.address, name, result.rssi) else discovered.add(Device(device.address, name, result.rssi))
                val target = connectTarget
                if (target != null && (target == "*" || name.equals(target, ignoreCase = true))) {
                    connectTarget = null
                    connect(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) { main.post { isScanning = false; state = "scan failed ($errorCode)"; fail(BlueClientException("scan failed ($errorCode)")) } }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true
                    state = "negotiating"
                    if (!gatt.requestMtu(185)) gatt.discoverServices()
                } else {
                    isConnected = false
                    request = null; response = null
                    fail(BlueClientException("disconnected"))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            main.post { this@BlueGattClient.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else BlueFramer.DEFAULT_MTU; gatt.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val service = gatt.getService(BlueUUIDs.SERVICE) ?: return@post fail(BlueClientException("the device does not expose the QRX service"))
                request = service.getCharacteristic(BlueUUIDs.REQUEST)
                response = service.getCharacteristic(BlueUUIDs.RESPONSE)
                val response = response ?: return@post fail(BlueClientException("the device does not expose the QRX service"))
                gatt.setCharacteristicNotification(response, true)
                val descriptor = response.getDescriptor(BlueUUIDs.CCCD)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                } else {
                    pending?.let { write(it.request) }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post { pending?.let { write(it.request) } }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                writing = false
                if (status != BluetoothGatt.GATT_SUCCESS) fail(BlueClientException("write failed ($status)")) else writeNext(gatt, characteristic)
            }
        }

        @Deprecated("Deprecated in API 33; the byte-array overload forwards here on older devices")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { received(it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            received(value)
        }
    }

    private fun received(value: ByteArray) {
        main.post {
            try {
                assembler.append(value)?.let { text -> finish(runCatching { BlueResponse.parse(text) }) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}
