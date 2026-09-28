/*
 * SpeechInput.kt
 * QRX
 *
 * Push-to-talk speech recognition with the platform SpeechRecognizer: the
 * microphone button starts a transcription and the final text is handed to
 * the scenes as a `spoken` event. Quest headsets may have no recogniser;
 * `isAvailable` says so.
 */
package com.bclnet.qrx.shared.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bclnet.qrx.shared.blue.BluePermissions

class SpeechInput(private val context: Context) {
    var isListening: Boolean by mutableStateOf(false)
        private set
    var transcript: String by mutableStateOf("")
        private set
    var error: String? by mutableStateOf(null)
    /** Called with the final text when listening stops. */
    var onHeard: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun toggle() { if (isListening) stop() else start() }

    fun start() {
        if (isListening) return
        if (!BluePermissions.has(context, BluePermissions.RECORD_AUDIO)) { error = "Microphone access is not allowed."; return }
        if (!isAvailable) { error = "Speech recognition is unavailable on this device."; return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(code: Int) { isListening = false; if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_CLIENT) error = "Speech recognition failed ($code)." }
            override fun onResults(results: Bundle?) {
                transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: transcript
                finish()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { transcript = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        transcript = ""
        error = null
        isListening = true
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    fun stop() {
        if (!isListening) return
        recognizer?.stopListening()
        finish()
    }

    private fun finish() {
        if (!isListening) return
        isListening = false
        val text = transcript.trim()
        if (text.isNotEmpty()) onHeard?.invoke(text)
    }

    fun release() { recognizer?.destroy(); recognizer = null }
}
