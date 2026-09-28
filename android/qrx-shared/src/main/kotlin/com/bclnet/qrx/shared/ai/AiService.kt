/*
 * AiService.kt
 * QRX
 *
 * The app's TokenX server: providers and keys entered by the user in the
 * settings panel, the usage ledger, and the JsonMind provider handed to the
 * scenes so actors' minds can think. Keys live encrypted in SQLite with the
 * cipher key in the Android Keystore.
 */
package com.bclnet.qrx.shared.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bclnet.jsonmind.tokenx.TokenXMindProvider
import com.bclnet.tokenx.InMemoryStore
import com.bclnet.tokenx.ProviderKind
import com.bclnet.tokenx.Profile
import com.bclnet.tokenx.Settings
import com.bclnet.tokenx.TokenClient
import com.bclnet.tokenx.TokenServer
import com.bclnet.tokenx.TokenStore
import com.bclnet.tokenx.UsageTotals
import com.bclnet.tokenx.android.AndroidSqlDatabase
import com.bclnet.tokenx.android.KeystoreCipher

class AiService(context: Context) {
    val server: TokenServer
    /** The provider scenes attach to their actors' minds. */
    val provider: TokenXMindProvider

    var settings: Settings by mutableStateOf(Settings())
        private set
    var configured: List<ProviderKind> by mutableStateOf(emptyList())
        private set
    var usageToday: UsageTotals by mutableStateOf(UsageTotals())
        private set
    var lastError: String? by mutableStateOf(null)

    init {
        val store: TokenStore = runCatching { AndroidSqlDatabase.store(context.applicationContext) }.getOrElse { InMemoryStore() }
        val cipher = runCatching { KeystoreCipher("net.bcl.qrx.tokenx") }.getOrElse { com.bclnet.tokenx.PlainCipher }
        server = TokenServer(store, cipher)
        provider = TokenXMindProvider(TokenClient(server))
        refresh()
    }

    val isReady: Boolean get() = server.isReady

    /** What answers a character right now, for the settings panel. */
    val characterModel: String? get() = server.model(Profile.CHARACTER)?.let { "${it.name} (${it.provider.displayName})" }

    fun refresh() {
        settings = server.settings
        configured = server.configuredProviders
        usageToday = server.usageToday()
    }

    fun activate(provider: ProviderKind, key: String?) {
        runCatching { server.activate(provider, key?.takeIf { it.isNotBlank() }); lastError = null }.onFailure { lastError = it.message }
        refresh()
    }

    fun removeKey(provider: ProviderKind) {
        runCatching { server.setKey(null, provider) }.onFailure { lastError = it.message }
        refresh()
    }

    fun update(change: (Settings) -> Settings) {
        runCatching { server.update(change) }.onFailure { lastError = it.message }
        refresh()
    }
}
