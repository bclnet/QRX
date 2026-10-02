/*
 * BluePermissions.kt
 * QRX
 *
 * Runtime permissions needed by the camera and the Bluetooth roles.
 */
package com.bclnet.qrx.shared.blue

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluePermissions {
    const val CAMERA = Manifest.permission.CAMERA
    /** Meta Quest passthrough camera access (Horizon OS v74+). */
    const val HEADSET_CAMERA = "horizonos.permission.HEADSET_CAMERA"
    /** Meta Quest spatial data: MRUK's QR code tracker (and the room model) need it. */
    const val USE_SCENE = "com.oculus.permission.USE_SCENE"
    const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

    /** Bluetooth permissions for this API level (scan, connect, advertise). */
    val bluetooth: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Everything a QRX app asks for at startup. */
    fun required(quest: Boolean = false): List<String> = listOf(CAMERA, RECORD_AUDIO) + (if (quest) listOf(HEADSET_CAMERA, USE_SCENE) else emptyList()) + bluetooth

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAll(context: Context, permissions: List<String>): Boolean = permissions.all { has(context, it) }

    fun hasConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || has(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) has(context, Manifest.permission.BLUETOOTH_SCAN) else has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasAdvertise(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || has(context, Manifest.permission.BLUETOOTH_ADVERTISE)
}
