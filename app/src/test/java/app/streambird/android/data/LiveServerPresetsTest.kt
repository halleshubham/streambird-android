package app.streambird.android.data

import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.preset.ApiPresets
import app.streambird.android.preset.PresetController
import app.streambird.android.preset.SetupChoices
import app.streambird.android.preset.choicesToConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Manual check against a REAL StreamBird server (the Nest app from the server repo booted on a local Postgres with its own e2e harness).
 * Runs only when the environment variable SB_LIVE_FILE points to a JSON file {baseUrl, cookie, connectionIds[], kitId}; otherwise it is skipped,
 * so CI and a fresh checkout stay green. It drives the app's real ApiClient + PresetController exactly like the Customize sheet does.
 */
class LiveServerPresetsTest {
    private val spec = System.getenv("SB_LIVE_FILE")?.let { File(it) }?.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun api(): ApiClient {
        val s = spec!!
        val base = s["baseUrl"]!!.jsonPrimitive.content
        val cookie = s["cookie"]!!.jsonPrimitive.content
        val http = OkHttpClient.Builder()
            .addInterceptor(OriginInterceptor { base })
            .addInterceptor(Interceptor { c -> c.proceed(c.request().newBuilder().header("Cookie", cookie).build()) })
            .build()
        return ApiClient(http) { base }
    }

    @Test fun `save, list, update, use and delete against the real server`() = runBlocking {
        assumeTrue("SB_LIVE_FILE not set", spec != null)
        val s = spec!!
        val ids = s["connectionIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        val kit = s["kitId"]!!.jsonPrimitive.content
        val ctl = PresetController(ApiPresets(api()))

        ctl.refresh()
        assertTrue("available after a good list", ctl.state.value.available)
        val before = ctl.state.value.items.size

        val choices = SetupChoices(linkedSetOf(ids[0], ids[1]), StreamTier.P720, StreamOrientation.PORTRAIT, kit, "Welcome to the show")
        val saved = ctl.save(null, "Friday show", choicesToConfig(choices))
        assertNotNull("save failed: ${ctl.state.value.error}", saved)
        assertEquals(before + 1, ctl.state.value.items.size)
        assertEquals(listOf(ids[0], ids[1]), saved!!.config.destinationIds)
        assertEquals("hd", saved.config.quality)

        // The server really has it (fresh controller = a process restart).
        val fresh = PresetController(ApiPresets(api()))
        fresh.refresh()
        assertTrue(fresh.state.value.items.any { it.id == saved.id && it.name == "Friday show" })

        // Update (PATCH) with no kit and no ticker.
        val updated = ctl.save(saved.id, "Friday show 2", choicesToConfig(choices.copy(brandKitId = null, tickerText = "", tier = StreamTier.P1080)))
        assertNotNull("update failed: ${ctl.state.value.error}", updated)
        assertEquals("fhd", updated!!.config.quality)
        assertEquals(false, updated.config.tickerEnabled)

        ctl.markUsed(saved.id)
        assertNotNull(ctl.state.value.items.first { it.id == saved.id }.lastUsedAt)
        assertEquals(saved.id, ctl.state.value.items.first().id)

        // Plan limit: a blank-ish and an over-long name are handled locally / by the server.
        assertNull(ctl.save(null, "   ", choicesToConfig(choices)))

        assertTrue(ctl.delete(saved.id))
        fresh.refresh()
        assertFalse(fresh.state.value.items.any { it.id == saved.id })
    }

    @Test fun `the 400 validation message reaches the user`() = runBlocking {
        assumeTrue("SB_LIVE_FILE not set", spec != null)
        val ctl = PresetController(ApiPresets(api()))
        ctl.refresh()
        val bad = ctl.save(null, "x", PresetConfig(destinationIds = listOf("not-a-uuid")))
        assertNull(bad)
        val msg = ctl.state.value.error.orEmpty()
        println("LIVE 400 message shown to the user: $msg")
        assertTrue("message was: $msg", msg.contains("UUID", ignoreCase = true))
    }
}
