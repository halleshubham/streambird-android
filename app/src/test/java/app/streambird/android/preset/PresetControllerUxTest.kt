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

/** Delete with Undo (fake clock), Duplicate and Rename of [PresetController] (1.0.16). */
class PresetControllerUxTest {
    private class Fake : PresetsPort {
        var rows = mutableListOf<Preset>()
        var failWrite: Exception? = null
        val calls = mutableListOf<String>()
        var created = mutableListOf<Pair<String, PresetConfig>>()
        var patches = mutableListOf<Triple<String, String?, PresetConfig?>>()
        var seq = 0
        override suspend fun list(): List<Preset> = rows.toList()
        override suspend fun create(name: String, config: PresetConfig): Preset { calls += "create"; failWrite?.let { throw it }; created += name to config; return Preset("n${++seq}", name, config).also { rows.add(it) } }
        override suspend fun update(id: String, name: String?, config: PresetConfig?): Preset {
            calls += "update"; failWrite?.let { throw it }; patches += Triple(id, name, config)
            val old = rows.firstOrNull { it.id == id } ?: throw ApiException(404, "Preset not found")
            return old.copy(name = name ?: old.name, config = config ?: old.config).also { n -> rows[rows.indexOf(old)] = n }
        }
        override suspend fun delete(id: String) { calls += "delete"; failWrite?.let { throw it }; rows.removeAll { it.id == id } }
        override suspend fun use(id: String): Preset = rows.first { it.id == id }
    }

    private val a = Preset("a", "Friday", PresetConfig(quality = "hd", destinationIds = listOf("c1"), visibility = "unlisted"), "2026-10-01T00:00:00Z")
    private val b = Preset("b", "Weekend", PresetConfig(quality = "fhd"), "2026-10-09T00:00:00Z")

    private class Clock { var now = 1_000L; fun get() = now }

    private fun setup(vararg rows: Preset): Triple<Fake, PresetController, Clock> {
        val f = Fake().apply { this.rows = rows.toMutableList() }
        val clock = Clock()
        return Triple(f, PresetController(f, clock::get) { }, clock)
    }

    // ---- delete with Undo ----

    @Test fun `begin hides the card at once and does not call the server`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        assertTrue(c.beginDelete(b))
        assertEquals(listOf("a"), c.state.value.items.map { it.id }); assertEquals("b", c.state.value.pendingDelete!!.id)
        assertTrue(f.calls.none { it == "delete" }); assertEquals(2, f.rows.size)
    }

    @Test fun `undo restores the exact preset in its place and the server was never touched`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        c.beginDelete(b)
        val back = c.undoDelete()
        assertEquals(b, back); assertNull(c.state.value.pendingDelete)
        assertEquals(listOf("b", "a"), c.state.value.items.map { it.id }) // most recently used first, as before
        assertTrue(f.calls.isEmpty())
        assertNull(c.undoDelete()) // nothing waiting
    }

    @Test fun `the timer commits only after the window, and not for another preset`() = runTest {
        val (f, c, clock) = setup(a, b); c.refresh()
        c.beginDelete(b)
        clock.now += UNDO_WINDOW_MS - 1
        assertFalse(c.commitDeleteIfDue("b")); assertTrue(f.calls.none { it == "delete" })
        clock.now += 1
        assertFalse("wrong id", c.commitDeleteIfDue("a"))
        assertTrue(c.commitDeleteIfDue("b"))
        assertEquals(listOf("delete"), f.calls); assertEquals(listOf("a"), f.rows.map { it.id })
        assertNull(c.state.value.pendingDelete); assertEquals("Preset deleted", c.state.value.notice)
        assertEquals(listOf("a"), c.state.value.items.map { it.id })
    }

    @Test fun `a re-read inside the window does not bring the hidden preset back`() = runTest {
        val (_, c, _) = setup(a, b); c.refresh()
        c.beginDelete(b)
        c.refresh()
        assertEquals(listOf("a"), c.state.value.items.map { it.id })
    }

    @Test fun `a second delete commits the first one now`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        c.beginDelete(b); c.beginDelete(a)
        assertEquals(listOf("a"), f.rows.map { it.id }) // b went to the server, a is waiting
        assertEquals("a", c.state.value.pendingDelete!!.id); assertTrue(c.state.value.items.isEmpty())
    }

    @Test fun `a failed server delete puts the preset back and says so`() = runTest {
        val (f, c, clock) = setup(a, b); c.refresh()
        c.beginDelete(b)
        f.failWrite = ApiException(500, "boom")
        clock.now += UNDO_WINDOW_MS
        assertFalse(c.commitDeleteIfDue("b"))
        assertEquals(setOf("a", "b"), c.state.value.items.map { it.id }.toSet())
        assertTrue(c.state.value.error!!.contains("Could not delete the preset: boom") && c.state.value.error!!.contains("put back"))
        assertNull(c.state.value.pendingDelete); assertFalse(c.state.value.busy)
        // and the next re-read keeps it
        f.failWrite = null; c.refresh(); assertEquals(2, c.state.value.items.size)
    }

    @Test fun `deleting what is already gone on the server counts as done`() = runTest {
        val (f, c, clock) = setup(a); c.refresh()
        c.beginDelete(a); f.rows.clear()
        f.failWrite = ApiException(404, "Preset not found")
        clock.now += UNDO_WINDOW_MS
        assertTrue(c.commitDeleteIfDue("a")); assertNull(c.state.value.error); assertTrue(c.state.value.items.isEmpty())
    }

    @Test fun `begin on an unknown preset does nothing, flush commits at once`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        assertFalse(c.beginDelete(Preset("zzz", "x")))
        c.beginDelete(a)
        assertTrue(c.commitPendingDelete()); assertEquals(listOf("b"), f.rows.map { it.id })
        assertFalse(c.commitPendingDelete())
    }

    @Test fun `creating a preset while a delete waits frees its slot first`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        c.beginDelete(b)
        assertNotNull(c.save(null, "New", PresetConfig()))
        assertEquals(listOf("delete", "create"), f.calls)
        assertNull(c.state.value.pendingDelete)
        assertTrue(c.state.value.items.none { it.id == "b" })
    }

    // ---- duplicate ----

    @Test fun `duplicate posts the same config under a unique copy name and confirms`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        val made = c.duplicate(a)
        assertNotNull(made)
        assertEquals(listOf("Friday copy" to a.config), f.created)
        assertEquals("Created \"Friday copy\"", c.state.value.notice); assertEquals(3, c.state.value.items.size)
        val again = c.duplicate(a)!!
        assertEquals("Friday copy 2", again.name)
        assertEquals("unlisted", again.config.visibility)
    }

    @Test fun `duplicate at the limit explains and does not call the server`() = runTest {
        val rows = List(MAX_PRESETS) { Preset("p$it", "P$it") }
        val (f, c, _) = setup(*rows.toTypedArray()); c.refresh()
        assertNull(c.duplicate(rows[0]))
        assertEquals(LIMIT_REACHED_TEXT, c.state.value.error); assertTrue(f.calls.isEmpty())
    }

    @Test fun `duplicate failures keep the list and say why`() = runTest {
        val (f, c, _) = setup(a); c.refresh()
        f.failWrite = ApiException(422, "brandKitId does not belong to this account")
        assertNull(c.duplicate(a))
        assertTrue(c.state.value.error!!.contains("Could not duplicate the preset") && c.state.value.error!!.contains("brandKitId does not belong"))
        f.failWrite = IOException("offline")
        assertNull(c.duplicate(a)); assertTrue(c.state.value.error!!.contains("Cannot reach"))
        assertEquals(1, c.state.value.items.size); assertFalse(c.state.value.busy)
    }

    // ---- rename ----

    @Test fun `rename sends the name only and keeps the config`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        val r = c.rename("a", "  Friday live  ")
        assertEquals("Friday live", r!!.name)
        assertEquals(listOf(Triple("a", "Friday live", null as PresetConfig?)), f.patches)
        assertEquals(a.config, c.state.value.items.first { it.id == "a" }.config)
        assertEquals("Renamed to \"Friday live\"", c.state.value.notice)
    }

    @Test fun `rename keeps the config when the server answers without one`() = runTest {
        val inner = Fake().apply { rows = mutableListOf(a) }
        val port = object : PresetsPort by inner {
            override suspend fun update(id: String, name: String?, config: PresetConfig?) = Preset(id, name ?: "", PresetConfig(), a.lastUsedAt)
        }
        val c = PresetController(port) { }; c.refresh()
        assertEquals(a.config, c.rename("a", "New")!!.config)
        assertEquals(a.config, c.state.value.items.single().config)
    }

    @Test fun `rename to the same name, a blank name or over the cap`() = runTest {
        val (f, c, _) = setup(a); c.refresh()
        assertEquals(a, c.rename("a", "Friday")); assertTrue(f.patches.isEmpty())
        assertNull(c.rename("a", "   ")); assertEquals("Give the preset a name.", c.state.value.error); assertTrue(f.patches.isEmpty())
        c.clearError()
        assertEquals(80, c.rename("a", "n".repeat(120))!!.name.length)
    }

    @Test fun `rename of a preset deleted elsewhere says so and drops the card`() = runTest {
        val (f, c, _) = setup(a, b); c.refresh()
        f.rows.removeAll { it.id == "a" }
        assertNull(c.rename("a", "Other"))
        assertTrue(c.state.value.error!!.contains("no longer exists")); assertEquals(listOf("b"), c.state.value.items.map { it.id })
    }

    @Test fun `reset forgets a waiting delete`() = runTest {
        val (_, c, _) = setup(a); c.refresh()
        c.beginDelete(a); c.reset()
        assertNull(c.state.value.pendingDelete); assertFalse(c.state.value.loaded)
    }
}
