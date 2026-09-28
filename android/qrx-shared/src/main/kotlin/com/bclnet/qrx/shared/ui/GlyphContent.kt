/*
 * GlyphContent.kt
 * QRX
 *
 * Renders a glyph document. `_ui` glyphs are JsonUI documents rendered with
 * JsonUIView and the session's host actions.
 */
package com.bclnet.qrx.shared.ui

import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bclnet.jsonui.JsonActionHandler
import com.bclnet.jsonui.compose.JsonUIView
import com.bclnet.jsonui.compose.rememberJsonUIModel
import com.bclnet.qrx.core.GlyphContent
import com.bclnet.qrx.core.GlyphDocument
import com.bclnet.qrx.shared.FoundGlyph
import com.bclnet.qrx.shared.GlyphSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** The card shown for a found glyph: a placeholder while loading, then its content. */
@Composable
fun GlyphCard(glyph: FoundGlyph, session: GlyphSession, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        val document = glyph.document
        when {
            glyph.error != null -> Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(glyph.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            document == null -> Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(glyph.barcode.location ?: glyph.barcode.payload, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
            else -> GlyphContentView(document, session)
        }
    }
}

@Composable
fun GlyphContentView(document: GlyphDocument, session: GlyphSession, modifier: Modifier = Modifier.fillMaxSize()) {
    when (val content = document.content) {
        is GlyphContent.Image -> RemoteImage(content.url, modifier.padding(8.dp))
        is GlyphContent.Video -> VideoView(content.url, content.loop, modifier)
        is GlyphContent.Web -> WebContentView(content.url, modifier)
        is GlyphContent.Button -> Box(modifier, contentAlignment = Alignment.Center) {
            Button(onClick = { session.perform(content.action) }) { Text(content.text, style = MaterialTheme.typography.titleMedium) }
        }
        is GlyphContent.Ui -> GlyphUIView(content, session, modifier)
        is GlyphContent.Unknown -> Column(modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Text("Unsupported glyph type \"${content.type}\"", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Hosts a JsonUI document with the session's host actions (toast, open, led, dismiss). */
@Composable
fun GlyphUIView(content: GlyphContent.Ui, session: GlyphSession, modifier: Modifier = Modifier) {
    val model = rememberJsonUIModel(content.document)
    LaunchedEffect(model) {
        model.actions.fallback = JsonActionHandler { name, args, context -> session.actions.invoke(name, args, context) }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        JsonUIView(model)
    }
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        val loaded = withContext(Dispatchers.IO) { runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull() }
        if (loaded != null) bitmap = loaded else failed = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            failed -> Text("Image failed", style = MaterialTheme.typography.bodySmall)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
fun VideoView(url: String, loop: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } }, modifier = modifier)
}

@Composable
fun WebContentView(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                @Suppress("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                loadUrl(url)
            }
        },
        modifier = modifier,
    )
}
