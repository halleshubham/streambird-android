package app.streambird.android.data

import app.streambird.android.preset.PresetController
import app.streambird.android.preset.PresetsPort
import app.streambird.android.ui.screens.deletionBullets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What "your account was deleted" does to the app: the session is gone for good, personal preferences are wiped, the user lands on sign-in. */
class AccountDeletedTest {
    private class Prefs : StreamPrefs {
        override var baseUrl = "https://streambird.app"
        override var lastEmail = "host@test.dev"
        override var tickerText = "Hello"
        override var tickerSize = "L"
    }

    private class Storage : CookieStorage {
        var value: String? = null
        var flushed = 0
        override fun load() = value
        override fun save(json: String) { value = json }
        override suspend fun flush() { flushed++ }
    }

    private fun jarWithSession(storage: Storage): PersistentCookieJar {
        val jar = PersistentCookieJar(storage)
        val url = "https://streambird.app/".toHttpUrl()
        jar.saveFromResponse(url, listOf(Cookie.Builder().name(AuthRepository.SESSION_COOKIE).value("tok").domain("streambird.app").path("/").expiresAt(System.currentTimeMillis() + 3_600_000).build()))
        return jar
    }

    @Test fun `accountDeleted clears session, preferences and goes to sign in with the message`() = runBlocking {
        val storage = Storage(); val jar = jarWithSession(storage); val prefs = Prefs()
        val api = ApiClient(OkHttpClient(), { "https://streambird.app" })
        val repo = AuthRepository(api, jar, prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertTrue(jar.hasCookie(AuthRepository.SESSION_COOKIE))

        repo.accountDeleted()

        assertFalse(jar.hasCookie(AuthRepository.SESSION_COOKIE))
        assertEquals("[]", storage.value) // the empty jar is what is on disk
        assertTrue(storage.flushed >= 1)
        assertEquals("", prefs.lastEmail); assertEquals("", prefs.tickerText); assertEquals("S", prefs.tickerSize)
        assertEquals("https://streambird.app", prefs.baseUrl) // app configuration stays
        val ui = repo.ui.value
        assertEquals(Stage.LOGGED_OUT, ui.stage); assertNull(ui.user); assertEquals("", ui.email); assertEquals("Your account was deleted.", ui.note); assertNull(ui.error)
    }

    @Test fun `presets are forgotten`() = runBlocking {
        val port = object : PresetsPort {
            override suspend fun list() = listOf(Preset("p", "P"))
            override suspend fun create(name: String, config: PresetConfig) = Preset("n", name)
            override suspend fun update(id: String, name: String?, config: PresetConfig?) = Preset(id, name.orEmpty())
            override suspend fun delete(id: String) {}
            override suspend fun use(id: String) = Preset(id, "P")
        }
        val c = PresetController(port)
        c.refresh(); assertTrue(c.state.value.items.isNotEmpty())
        c.reset()
        assertTrue(c.state.value.items.isEmpty()); assertFalse(c.state.value.loaded)
    }

    @Test fun `the list of what is deleted follows the preview`() {
        val plain = deletionBullets(DeletionPreview())
        assertEquals(4, plain.size)
        assertTrue(plain.any { it.startsWith("Your streams") }); assertTrue(plain.any { it.contains("destinations") })
        assertTrue(plain.any { it.contains("brand kits and uploaded files") }); assertTrue(plain.any { it.contains("presets") })
        val all = deletionBullets(DeletionPreview(true, 3, ActiveSubscription("Pro", null), true, 2, 1))
        assertTrue(all.any { it.startsWith("The whole company account") && it.contains("2 other members") })
        assertTrue(all.any { it.contains("autopay will be cancelled") })
        assertTrue(all.any { it.contains("live right now will be ended") })
        assertTrue(all.any { it.contains("1 file") && !it.contains("1 files") })
        assertTrue(deletionBullets(DeletionPreview(true, 1)).any { it == "The whole company account" })
    }
}
