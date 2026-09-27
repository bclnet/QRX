/*
 * GlyphScanner.kt
 * QRX
 *
 * CameraX + ML Kit QR detection. Every analysed frame publishes the codes it
 * contains with their corner points in rotated image coordinates, so the
 * overlay can follow the codes. Used by the phone app (with a PreviewView)
 * and by the Quest app (analysis only, from the passthrough camera).
 */
package com.bclnet.qrx.shared.scan

import android.content.Context
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** One detected code. Points are in rotated image coordinates (upright frame). */
data class Detection(val payload: String, val corners: List<Pair<Float, Float>>) {
    val bounds: Box? get() = GlyphPlacement.bounds(corners)
}

/** The detections of one frame with the (rotated) image size they refer to. */
data class ScanFrame(val detections: List<Detection>, val imageWidth: Int, val imageHeight: Int, val timestampMs: Long)

class GlyphScanner(private val context: Context) {
    private val _frames = MutableStateFlow(ScanFrame(emptyList(), 1, 1, 0))
    val frames: StateFlow<ScanFrame> = _frames

    private val executor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private var provider: ProcessCameraProvider? = null

    /** Camera selector; the Quest app overrides it to pick the passthrough camera. */
    var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    var analysisResolution: Size = Size(1280, 720)

    /** Starts the camera; `preview` may be null when only analysis is needed. */
    fun start(owner: LifecycleOwner, preview: Preview? = null, onError: (Throwable) -> Unit = {}) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                this.provider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                provider.unbindAll()
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                provider.bindToLifecycle(owner, cameraSelector, *useCases)
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val upright = rotation == 90 || rotation == 270
        val width = if (upright) proxy.height else proxy.width
        val height = if (upright) proxy.width else proxy.height
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val detections = barcodes.mapNotNull { barcode ->
                    val payload = barcode.rawValue ?: return@mapNotNull null
                    val corners = barcode.cornerPoints?.map { it.x.toFloat() to it.y.toFloat() }
                        ?: barcode.boundingBox?.let { listOf(it.left.toFloat() to it.top.toFloat(), it.right.toFloat() to it.top.toFloat(), it.right.toFloat() to it.bottom.toFloat(), it.left.toFloat() to it.bottom.toFloat()) }
                        ?: return@mapNotNull null
                    Detection(payload, corners)
                }
                _frames.value = ScanFrame(detections, width, height, System.currentTimeMillis())
            }
            .addOnCompleteListener { proxy.close() }
    }
}
