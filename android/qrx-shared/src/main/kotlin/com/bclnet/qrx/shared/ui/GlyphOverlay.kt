/*
 * GlyphOverlay.kt
 * QRX
 *
 * The camera preview with glyph cards drawn over the codes the scanner
 * currently sees, placed with GlyphPlacement. Codes not seen for a while
 * fade out; `multi` glyphs get a card per code occurrence.
 */
package com.bclnet.qrx.shared.ui

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.bclnet.qrx.core.GlyphSelector
import com.bclnet.qrx.shared.GlyphSession
import com.bclnet.qrx.shared.scan.GlyphPlacement
import com.bclnet.qrx.shared.scan.GlyphScanner
import com.bclnet.qrx.shared.scan.ImageToView
import kotlin.math.roundToInt

/** How long a glyph card stays after its code was last seen, ms. */
const val GLYPH_LINGER_MS = 1500L

@Composable
fun GlyphOverlay(session: GlyphSession, scanner: GlyphScanner, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val preview = remember { Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider } }
    val frame by scanner.frames.collectAsState()

    DisposableEffect(scanner) {
        scanner.start(owner, preview) { session.title = "Camera failed: ${it.message}" }
        onDispose { scanner.stop() }
    }
    LaunchedEffect(frame) { frame.detections.forEach { session.seen(it.payload) } }

    BoxWithConstraints(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        val density = LocalDensity.current
        val viewWidth = with(density) { maxWidth.toPx() }
        val viewHeight = with(density) { maxHeight.toPx() }
        val mapping = ImageToView(frame.imageWidth.toFloat(), frame.imageHeight.toFloat(), viewWidth, viewHeight)
        val now = System.currentTimeMillis()
        for (detection in frame.detections) {
            val glyph = session.glyph(detection.payload) ?: continue
            if (now - glyph.lastSeen > GLYPH_LINGER_MS) continue
            val code = detection.bounds?.let { mapping.map(it) } ?: continue
            val box = GlyphPlacement.place(code, glyph.barcode.size(GlyphSelector.Normal))
            val widthDp = with(density) { box.width.toDp() }
            val heightDp = with(density) { box.height.toDp() }
            Box(
                Modifier
                    .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                    .size(widthDp, heightDp),
            ) {
                GlyphCard(glyph, session, Modifier.fillMaxSize())
            }
        }
    }
}
