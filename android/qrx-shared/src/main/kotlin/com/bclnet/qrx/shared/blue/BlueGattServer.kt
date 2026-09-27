/*
 * BlueGattServer.kt
 * QRX
 *
 * BLUE/1.0 server over Android Bluetooth LE: advertises the QRX service,
 * reassembles request chunks written by centrals, routes them through the
 * BlueRouter and notifies the response chunks one at a time (waiting for
 * onNotificationSent between chunks).
 */
package com.bclnet.qrx.shared.blue

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bclnet.qrx.core.blue.BlueAssembler
import com.bclnet.qrx.core.blue.BlueFramer
import com.bclnet.qrx.core.blue.BlueRouter
import com.bclnet.qrx.core.blue.BlueUUIDs
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BlueGattServer(private val context: Context, private val adapter: BluetoothAdapter?, private val router: BlueRouter) {
    var state: String by mutableStateOf("stopped")
        private set
    var subscriberCount: Int by mutableIntStateOf(0)
        private set
    var requestCount: Int by mutableIntStateOf(0)
        private set
    var localName: String = BlueUUIDs.DEFAULT_LOCAL_NAME
        set(value) { field = value; if (isRunning) { stopAdvertising(); startAdvertising() } }

    private var server: BluetoothGattServer? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private val assemblers = ConcurrentHashMap<String, BlueAssembler>()
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val outgoing = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val sending = ConcurrentHashMap<String, Boolean>()
    private var isRunning = false
    private var advertising = false

    fun start() {
        if (isRunning) return
        val adapter = adapter ?: run { state = "no Bluetooth adapter"; return }
        if (!adapter.isEnabled) { state = "Bluetooth is off"; return }
        if (!BluePermissions.hasConnect(context) || !BluePermissions.hasAdvertise(context)) { state = "Bluetooth permission missing"; return }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val server = manager.openGattServer(context, callback) ?: run { state = "could not open GATT server"; return }
        this.server = server
        val request = BluetoothGattCharacteristic(BlueUUIDs.REQUEST, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val response = BluetoothGattCharacteristic(BlueUUIDs.RESPONSE, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        response.addDescriptor(BluetoothGattDescriptor(BlueUUIDs.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service = BluetoothGattService(BlueUUIDs.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(request)
        service.addCharacteristic(response)
        server.addService(service)
        responseCharacteristic = response
        isRunning = true
        startAdvertising()
    }

    fun stop() {
        if (!isRunning) return
        stopAdvertising()
        server?.close()
        server = null
        isRunning = false
        subscribers.clear(); assemblers.clear(); outgoing.clear(); sending.clear()
        subscriberCount = 0
        state = "stopped"
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { state = "advertising not supported"; return }
        runCatching { adapter?.name = localName }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(BlueUUIDs.SERVICE)).setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; state = "advertising as $localName" }
        override fun onStartFailure(errorCode: Int) { advertising = false; state = "advertising failed ($errorCode)" }
    }

    private fun handle(device: BluetoothDevice, text: String) {
        requestCount++
        val response = router.handle(text)
        val mtu = mtus[device.address] ?: BlueFramer.DEFAULT_MTU
        val queue = outgoing.getOrPut(device.address) { ArrayDeque() }
        synchronized(queue) { BlueFramer.frames(response.text, mtu).forEach { queue.add(it) } }
        flush(device)
    }

    private fun flush(device: BluetoothDevice) {
        val server = server ?: return
        val characteristic = responseCharacteristic ?: return
        val queue = outgoing[device.address] ?: return
        if (sending[device.address] == true) return
        val next = synchronized(queue) { queue.pollFirst() } ?: return
        sending[device.address] = true
        characteristic.value = next
        if (!server.notifyCharacteristicChanged(device, characteristic, false)) sending[device.address] = false
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                subscribers.remove(device.address); assemblers.remove(device.address); outgoing.remove(device.address); sending.remove(device.address)
                subscriberCount = subscribers.size
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { mtus[device.address] = mtu }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor.uuid == BlueUUIDs.CCCD) {
                val enable = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enable) { subscribers[device.address] = device; assemblers[device.address] = BlueAssembler() } else subscribers.remove(device.address)
                subscriberCount = subscribers.size
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (subscribers.containsKey(device.address)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (characteristic.uuid != BlueUUIDs.REQUEST || value == null) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            val assembler = assemblers.getOrPut(device.address) { BlueAssembler() }
            val status = try {
                assembler.append(value)?.let { handle(device, it) }
                BluetoothGatt.GATT_SUCCESS
            } catch (e: Exception) {
                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
            }
            if (responseNeeded) server?.sendResponse(device, requestId, status, offset, value)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            // Reads return the next pending chunk, for centrals that cannot subscribe.
            val queue = outgoing[device.address]
            val next = queue?.let { synchronized(it) { it.pollFirst() } } ?: ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, next)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sending[device.address] = false
            flush(device)
        }
    }
}
