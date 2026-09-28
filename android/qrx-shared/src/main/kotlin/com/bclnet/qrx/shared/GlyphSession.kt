/*
 * GlyphSession.kt
 * QRX
 *
 * State shared by the phone and Quest apps: the glyphs found so far, their
 * documents, the status message, toasts, and the JsonUI host actions
 * offered to `_ui` glyphs (`toast`, `open`, `led`, `dismiss`). The Android
 * counterpart of the iOS AppModel.
 */
package com.bclnet.qrx.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.JsonActionHandler
import com.bclnet.jsonui.JsonActions
import com.bclnet.jsonui.JsonRuntime
import com.bclnet.jsonui.get
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.stringValue
import com.bclnet.jsonui.toJsonString
import com.bclnet.qrx.core.GlyphBarcode
import com.bclnet.qrx.core.GlyphDocument
import com.bclnet.qrx.core.GlyphLookup
import com.bclnet.qrx.core.blue.LedColor
import com.bclnet.qrx.shared.ai.AiService
import com.bclnet.qrx.shared.blue.BluetoothService
import com.bclnet.qrx.shared.speech.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FoundGlyph(val barcode: GlyphBarcode) {
    var document: GlyphDocument? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    /** Last time the code was seen by the scanner, ms. */
    var lastSeen: Long by mutableStateOf(System.currentTimeMillis())
    val id: String get() = barcode.id
    val status: String get() = error ?: document?.content?.typeName ?: "loading"
}

class GlyphSession(private val context: Context, val bluetooth: BluetoothService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val found = mutableStateListOf<FoundGlyph>()
    var title: String by mutableStateOf("Look for a QR code.")
    var toast: String? by mutableStateOf(null)
    var flashOn: Boolean by mutableStateOf(false)
    var showSettings: Boolean by mutableStateOf(false)

    /** Host actions offered to `_ui` glyphs and `_button` actions. */
    val actions = JsonActions()
    val lookup = GlyphLookup(blue = bluetooth.client)
    /** TokenX: providers, keys and usage, plus the JsonMind provider for scene actors. */
    val ai = AiService(context)
    /** Push-to-talk speech, delivered to scenes as `spoken` events. */
    val speech = SpeechInput(context)
    /** Scenes register here to receive what the user said. */
    val heardHandlers = mutableListOf<(String) -> Unit>()

    init {
        registerActions()
        shareBundledExamples()
        speech.onHeard = { heard(it) }
    }

    /** Delivers recognised speech to every scene on the stage. */
    fun heard(text: String) {
        showToast("“$text”")
        heardHandlers.toList().forEach { it(text) }
    }

    fun glyph(payload: String): FoundGlyph? = found.firstOrNull { it.id == payload }

    /** Called by the scanner for every code in a frame; resolves new ones. */
    fun seen(payload: String) {
        val existing = glyph(payload)
        if (existing != null) { existing.lastSeen = System.currentTimeMillis(); return }
        val glyph = FoundGlyph(GlyphBarcode(payload))
        found.add(glyph)
        title = if (glyph.barcode.isBluetooth) "Fetching over Bluetooth…" else "Loading glyph…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { lookup.lookup(glyph.barcode) } }
            result.onSuccess { document ->
                glyph.document = document
                title = "Showing ${document.content.typeName} glyph"
            }.onFailure { error ->
                glyph.error = error.message ?: error.toString()
                title = "Glyph failed: ${glyph.error}"
            }
        }
    }

    fun forgetGlyphs() {
        found.clear()
        lookup.clearCache()
        title = "Look for a QR code."
    }

    fun showToast(message: String) {
        toast = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** Performs a `_button` action. */
    fun perform(action: JsonAction?) {
        if (action == null) { showToast("Tapped"); return }
        JsonRuntime(actions = actions).context.perform(action)
    }

    private fun registerActions() {
        actions.register("toast", JsonActionHandler { _, args, _ -> showToast(args["message"].stringValue ?: args.stringValue ?: "Done"); null })
        actions.register("open", JsonActionHandler { _, args, _ ->
            (args["url"].stringValue ?: args.stringValue)?.let { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            null
        })
        actions.register("led", JsonActionHandler { _, args, _ ->
            val color = LedColor.fromJson(args) ?: return@JsonActionHandler jsonObjectOf("error" to "expected r, g, b")
            jsonObjectOf("written" to bluetooth.led.write(color))
        })
        actions.register("dismiss", JsonActionHandler { _, _, _ -> forgetGlyphs(); null })
        actions.fallback = JsonActionHandler { name, args, _ -> showToast("$name ${args.toJsonString()}"); null }
    }

    /** Bundled example documents are shared over Bluetooth so another QRX can fetch them with `blue://` codes. */
    private fun shareBundledExamples() {
        val names = runCatching { context.assets.list("examples")?.toList() }.getOrNull() ?: return
        for (name in names.filter { it.endsWith(".json") }) {
            runCatching { GlyphDocument.parse(context.assets.open("examples/$name").bufferedReader().readText()) }
                .onSuccess { bluetooth.glyphService.share(it, name.removeSuffix(".json")) }
        }
    }
}
