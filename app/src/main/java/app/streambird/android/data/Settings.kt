package app.streambird.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.streambird.android.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "streambird")

/** What the stream controller needs from the settings (an interface so it can be faked in JVM tests). */
interface StreamPrefs {
    var baseUrl: String
    var lastEmail: String
    var tickerText: String
    var tickerSize: String
}

/**
 * Small persisted state: server URL, cookies, last-used email, ticker. DataStore, app-private (allowBackup is off).
 * Loaded ONCE in the background right when this object is created (Application.onCreate injects it), served from memory afterwards;
 * a getter that runs before the load finished waits for it (normally already done by then, and mostly called off the main thread).
 * All writes go through one [SerialWriter], so they are applied in order.
 */
class Settings(context: Context, private val scope: CoroutineScope) : StreamPrefs {
    private val store = context.applicationContext.dataStore
    private val writer = SerialWriter()
    private val loaded = CompletableDeferred<Unit>()

    @Volatile private var server: String = BuildConfig.DEFAULT_BASE_URL
    @Volatile private var email: String = ""
    @Volatile private var cookies: String? = null
    @Volatile private var tText: String = ""
    @Volatile private var tSize: String = "S"

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val p = store.data.first()
                p[KEY_SERVER]?.let { s -> runCatching { normalizeBaseUrl(s) }.getOrNull()?.let { server = it } }
                email = p[KEY_EMAIL].orEmpty(); cookies = p[KEY_COOKIES]; tText = p[KEY_TICKER_TEXT].orEmpty(); tSize = p[KEY_TICKER_SIZE] ?: "S"
            }
            loaded.complete(Unit)
        }
    }

    private fun ready() { if (!loaded.isCompleted) runBlocking { loaded.await() } }

    private fun put(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        writer.submit { runBlocking { store.edit { it[key] = value } } }
    }

    override var baseUrl: String
        get() { ready(); return server }
        set(value) { server = value; put(KEY_SERVER, value) }

    override var lastEmail: String
        get() { ready(); return email }
        set(value) { email = value; put(KEY_EMAIL, value) }

    /** Last ticker text and size (S/M/L as the enum name), remembered between runs. */
    override var tickerText: String
        get() { ready(); return tText }
        set(value) { tText = value; put(KEY_TICKER_TEXT, value) }

    override var tickerSize: String
        get() { ready(); return tSize }
        set(value) { tSize = value; put(KEY_TICKER_SIZE, value) }

    val cookieStorage = object : CookieStorage {
        override fun load(): String? { ready(); return cookies }
        override fun save(json: String) { cookies = json; put(KEY_COOKIES, json) }
        override suspend fun flush() { writer.flush() }
    }

    private companion object {
        val KEY_SERVER = stringPreferencesKey("server_url")
        val KEY_EMAIL = stringPreferencesKey("last_email")
        val KEY_COOKIES = stringPreferencesKey("cookies")
        val KEY_TICKER_TEXT = stringPreferencesKey("ticker_text")
        val KEY_TICKER_SIZE = stringPreferencesKey("ticker_size")
    }
}
