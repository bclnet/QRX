/*
 * AiService.kt
 * QRX
 *
 * The app's TokenX: the standard server (keys encrypted in SQLite, cipher
 * key in the Android Keystore) behind TokenXModel, plus the JsonMind provider
 * handed to the scenes so actors' minds can think. Settings UI comes from
 * tokenx-compose.
 */
package com.bclnet.qrx.shared.ai

import android.content.Context
import com.bclnet.jsonmind.tokenx.TokenXMindProvider
import com.bclnet.tokenx.compose.TokenXModel

class AiService(context: Context) {
    /** Settings, usage and commands; the settings sheet embeds TokenXSettings(model) on it. */
    val model = TokenXModel(context, appId = "net.bcl.qrx")
    /** The provider scenes attach to their actors' minds. */
    val provider = TokenXMindProvider(model.client)

    val isReady: Boolean get() = model.isReady
}
