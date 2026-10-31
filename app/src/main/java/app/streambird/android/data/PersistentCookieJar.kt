package app.streambird.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Where cookies survive process death. Production: DataStore; tests: an in-memory fake. */
interface CookieStorage {
    fun load(): String?
    /** Queued; writes are applied in call order. */
    fun save(json: String)
    /** Returns once every save queued so far is on disk. */
    suspend fun flush() {}
}

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

/**
 * Keeps the `sb_session` cookie (httpOnly, sameSite=lax, expires server-side) across restarts, so the app stays signed in
 * the same way the browser does. Thread-safe; expired cookies are dropped on load and on every lookup.
 */
class PersistentCookieJar(private val storage: CookieStorage) : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    init {
        val raw = storage.load()
        if (!raw.isNullOrBlank()) {
            runCatching { ApiJson.decodeFromString<List<StoredCookie>>(raw) }.getOrNull()?.forEach { s ->
                val b = Cookie.Builder().name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt)
                if (s.hostOnly) b.hostOnlyDomain(s.domain) else b.domain(s.domain)
                if (s.secure) b.secure()
                if (s.httpOnly) b.httpOnly()
                cookies += b.build()
            }
            dropExpired()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (c in cookies) {
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                // A cleared cookie arrives as an already-expired one (res.clearCookie): just don't keep it.
                if (c.expiresAt > System.currentTimeMillis()) this.cookies += c
            }
            persistLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        dropExpired()
        cookies.filter { it.matches(url) }
    }

    fun hasCookie(name: String): Boolean = synchronized(lock) { dropExpired(); cookies.any { it.name == name } }

    fun clear() = synchronized(lock) { cookies.clear(); persistLocked() }

    suspend fun flush() = storage.flush()

    private fun dropExpired() {
        val now = System.currentTimeMillis()
        if (cookies.removeAll { it.expiresAt <= now }) persistLocked()
    }

    private fun persistLocked() {
        val stored = cookies.map {
            StoredCookie(it.name, it.value, it.expiresAt, it.domain, it.path, it.secure, it.httpOnly, it.hostOnly)
        }
        storage.save(ApiJson.encodeToString(stored))
    }
}
