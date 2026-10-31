package app.streambird.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.streambird.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "streambird")

/** Small persisted state: server URL, cookies, last-used email. DataStore, app-private (allowBackup is off). */
class Settings(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    @Volatile
    private var baseUrlCache: String = runBlocking {
        store.data.first()[KEY_SERVER]?.let { runCatching { normalizeBaseUrl(it) }.getOrNull() } ?: BuildConfig.DEFAULT_BASE_URL
    }

    var baseUrl: String
        get() = baseUrlCache
        set(value) {
            baseUrlCache = value
            scope.launch { store.edit { it[KEY_SERVER] = value } }
        }

    var lastEmail: String
        get() = runBlocking { store.data.first()[KEY_EMAIL].orEmpty() }
        set(value) { scope.launch { store.edit { it[KEY_EMAIL] = value } } }

    /** Last ticker text and size (S/M/L as the enum name), remembered between runs. */
    var tickerText: String
        get() = runBlocking { store.data.first()[KEY_TICKER_TEXT].orEmpty() }
        set(value) { scope.launch { store.edit { it[KEY_TICKER_TEXT] = value } } }

    var tickerSize: String
        get() = runBlocking { store.data.first()[KEY_TICKER_SIZE] ?: "S" }
        set(value) { scope.launch { store.edit { it[KEY_TICKER_SIZE] = value } } }

    val cookieStorage = object : CookieStorage {
        override fun load(): String? = runBlocking { store.data.first()[KEY_COOKIES] }
        override fun save(json: String) { scope.launch { store.edit { it[KEY_COOKIES] = json } } }
    }

    private companion object {
        val KEY_SERVER = stringPreferencesKey("server_url")
        val KEY_EMAIL = stringPreferencesKey("last_email")
        val KEY_TICKER_TEXT = stringPreferencesKey("ticker_text")
        val KEY_TICKER_SIZE = stringPreferencesKey("ticker_size")
        val KEY_COOKIES = stringPreferencesKey("cookies")
    }
}
