package app.streambird.android.preset

import app.streambird.android.data.ApiException
import app.streambird.android.data.Preset
import app.streambird.android.data.PresetConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PresetControllerTest {
    private class Fake : PresetsPort {
        var rows = mutableListOf<Preset>()
        var failList: Exception? = null
        var failWrite: Exception? = null
        var failUse: Exception? = null
        var used = mutableListOf<String>()
        var seq = 0
        override suspend fun list(): List<Preset> { failList?.let { throw it }; return rows.toList() }
        override suspend fun create(name: String, config: PresetConfig): Preset { failWrite?.let { throw it }; return Preset("n${++seq}", name, config).also { rows.add(it) } }
        override suspend fun update(id: String, name: String?, config: PresetConfig?): Preset {
            failWrite?.let { throw it }
            val old = rows.first { it.id == id }
            return old.copy(name = name ?: old.name, config = config ?: old.config).also { n -> rows[rows.indexOf(old)] = n }
        }
        override suspend fun delete(id: String) { failWrite?.let { throw it }; rows.removeAll { it.id == id } }
        override suspend fun use(id: String): Preset { failUse?.let { throw it }; used += id; return rows.first { it.id == id }.copy(lastUsedAt = "2026-11-03T00:00:00Z") }
    }

    private val a = Preset("a", "A", PresetConfig(quality = "hd"), "2026-10-01T00:00:00Z")
    private val b = Preset("b", "B", PresetConfig(quality = "fhd"), "2026-10-09T00:00:00Z")

    @Test fun `refresh loads and orders the presets`() = runTest {
        val c = PresetController(Fake().apply { rows = mutableListOf(a, b) })
        assertFalse(c.state.value.loaded)
        c.refresh()
        assertTrue(c.state.value.loaded && c.state.value.available)
        assertEquals(listOf("b", "a"), c.state.value.items.map { it.id })
    }

    @Test fun `404 hides the presets section`() = runTest {
        val c = PresetController(Fake().apply { failList = ApiException(404, "Cannot GET /api/presets") })
        c.refresh()
        assertTrue(c.state.value.loaded); assertFalse(c.state.value.available); assertTrue(c.state.value.items.isEmpty())
    }


    @Test fun `offline, a captive page or a 5xx at start-up never hide the presets section, they say the load failed`() = runTest {
        val logs = mutableListOf<String>()
        val f = Fake().apply { failList = IOException("offline") }
        val c = PresetController(f) { logs += it }
        c.refresh()
        assertTrue(c.state.value.available); assertTrue(c.state.value.loaded); assertTrue(c.state.value.loadError!!.contains("Cannot reach the server"))
        f.failList = ApiException(-1, "unexpected answer"); c.refresh(); assertTrue(c.state.value.available); assertNotNull(c.state.value.loadError)
        f.failList = ApiException(500, "boom", "{\"statusCode\":500}"); c.refresh(); assertTrue(c.state.value.available)
        assertTrue(c.state.value.loadError!!.contains("boom"))
        assertTrue(logs.any { it.contains("HTTP 500") && it.contains("statusCode") })
        f.failList = null; f.rows = mutableListOf(a); c.refresh()
        assertNull(c.state.value.loadError); assertEquals(1, c.state.value.items.size)
    }

    @Test fun `404, 405 and 501 mean the server has no presets`() = runTest {
        for (code in listOf(404, 405, 501)) {
            val c = PresetController(Fake().apply { failList = ApiException(code, "x") })
            c.refresh()
            assertFalse("status $code", c.state.value.available)
        }
        assertFalse(isFeatureMissing(500)); assertFalse(isFeatureMissing(401)); assertFalse(isFeatureMissing(-1))
    }

    @Test fun `saving confirms with a notice and the list is re-read from the server`() = runTest {
        val f = Fake().apply { rows = mutableListOf(a) }
        val c = PresetController(f); c.refresh()
        f.rows.add(Preset("web", "From the website", PresetConfig(), null)) // created elsewhere meanwhile
        val made = c.save(null, "Mine", PresetConfig(quality = "sd"))
        assertNotNull(made)
        assertEquals("Preset saved", c.state.value.notice)
        assertEquals(setOf("a", "web", made!!.id), c.state.value.items.map { it.id }.toSet())
        c.save(made.id, "Mine 2", PresetConfig()); assertEquals("Preset updated", c.state.value.notice)
        c.clearNotice(); assertNull(c.state.value.notice)
    }

    @Test fun `the first failure of a write is logged with status and body, an unexpected exception cannot crash`() = runTest {
        val logs = mutableListOf<String>()
        val f = Fake().apply { failWrite = ApiException(400, "bad", "{\"message\":{\"message\":[\"x\"]}}") }
        val c = PresetController(f) { logs += it }
        c.refresh()
        assertNull(c.save(null, "x", PresetConfig()))
        assertTrue(logs.any { it.startsWith("presets: create failed HTTP 400 bad body=") })
        f.failWrite = IllegalStateException("kaboom")
        assertNull(c.save(null, "x", PresetConfig()))
        assertTrue(c.state.value.error!!.contains("IllegalStateException")); assertFalse(c.state.value.busy)
    }

    @Test fun `a 404 on create hides the section only when nothing is known, a 404 on update drops that preset`() = runTest {
        val f = Fake().apply { rows = mutableListOf(a) }
        val c = PresetController(f); c.refresh()
        f.failWrite = ApiException(404, "Preset not found")
        assertNull(c.save("a", "A", PresetConfig()))
        assertTrue(c.state.value.error!!.contains("no longer exists")); assertTrue(c.state.value.items.isEmpty()); assertTrue(c.state.value.available)
        assertNull(c.save(null, "new", PresetConfig())); assertFalse("feature missing and nothing known", c.state.value.available)
    }

    @Test fun `a network error after a good load keeps the cards, a later 404 removes them`() = runTest {
        val f = Fake().apply { rows = mutableListOf(a) }
        val c = PresetController(f)
        c.refresh()
        f.failList = IOException("offline"); c.refresh()
        assertTrue(c.state.value.available); assertEquals(listOf("a"), c.state.value.items.map { it.id })
        f.failList = ApiException(404, "gone"); c.refresh()
        assertFalse(c.state.value.available); assertTrue(c.state.value.items.isEmpty())
    }

    @Test fun `recovery after the server gets the feature`() = runTest {
        val f = Fake().apply { failList = ApiException(404, "x") }
        val c = PresetController(f)
        c.refresh(); assertFalse(c.state.value.available)
        f.failList = null; f.rows = mutableListOf(a); c.refresh()
        assertTrue(c.state.value.available)
    }

    @Test fun `save creates then updates, trims the name, and keeps the list in order`() = runTest {
        val f = Fake().apply { rows = mutableListOf(a, b) }
        val c = PresetController(f); c.refresh()
        val made = c.save(null, "  Weekend  ", PresetConfig(quality = "sd"))
        assertNotNull(made); assertEquals("Weekend", made!!.name)
        assertEquals(3, c.state.value.items.size)
        val upd = c.save(made.id, "Weekend 2", PresetConfig(quality = "hd"))
        assertEquals("Weekend 2", upd!!.name); assertEquals("hd", upd.config.quality)
        assertEquals(3, c.state.value.items.size)
        assertFalse(c.state.value.busy); assertNull(c.state.value.error)
    }

    @Test fun `a blank name is refused without calling the server`() = runTest {
        val f = Fake(); val c = PresetController(f)
        assertNull(c.save(null, "   ", PresetConfig()))
        assertEquals("Give the preset a name.", c.state.value.error); assertTrue(f.rows.isEmpty())
        c.clearError(); assertNull(c.state.value.error)
    }

    @Test fun `server and network errors on save become a message and keep the list`() = runTest {
        val f = Fake().apply { rows = mutableListOf(a) }
        val c = PresetController(f); c.refresh()
        f.failWrite = ApiException(400, "name too long")
        assertNull(c.save(null, "x", PresetConfig())); assertEquals("Could not save the preset: name too long", c.state.value.error)
        f.failWrite = IOException("offline")
        assertNull(c.save(null, "x", PresetConfig())); assertTrue(c.state.value.error!!.contains("Cannot reach"))
        assertTrue(c.state.value.available); assertEquals(1, c.state.value.items.size); assertFalse(c.state.value.busy)
    }

    @Test fun `delete removes the card`() = runTest {
        val f = Fake().apply { rows = mutableListOf(a, b) }
        val c = PresetController(f); c.refresh()
        assertTrue(c.delete("a")); assertEquals(listOf("b"), c.state.value.items.map { it.id })
        f.failWrite = ApiException(500, "boom")
        assertFalse(c.delete("b")); assertEquals(1, c.state.value.items.size); assertEquals("Could not delete the preset: boom", c.state.value.error)
    }

    @Test fun `using a preset moves it to the front and never throws`() = runTest {
        val f = Fake().apply { rows = mutableListOf(a, b) }
        val c = PresetController(f); c.refresh()
        c.markUsed("a")
        assertEquals(listOf("a", "b"), c.state.value.items.map { it.id }); assertEquals(listOf("a"), f.used)
        f.failUse = IOException("offline")
        c.markUsed("b")
        assertEquals(listOf("a", "b"), c.state.value.items.map { it.id }); assertNull(c.state.value.error)
    }

    @Test fun `using keeps the config when the server answers without one`() = runTest {
        val inner = Fake().apply { rows = mutableListOf(a) }
        val f = object : PresetsPort by inner {
            override suspend fun use(id: String) = Preset(id, "A", PresetConfig(), "2026-11-03T00:00:00Z")
        }
        val c = PresetController(f); c.refresh()
        c.markUsed("a")
        assertEquals("hd", c.state.value.items.single().config.quality)
    }
}
