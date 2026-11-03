package app.streambird.android.account

import app.streambird.android.data.ApiException
import app.streambird.android.data.DeletionPreview
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountControllerTest {
    private class Fake : AccountPort {
        var preview: DeletionPreview = DeletionPreview()
        var failPreview: Exception? = null
        var failRequest: Exception? = null
        var failDelete: Exception? = null
        val calls = mutableListOf<String>()
        val deleteBodies = mutableListOf<Pair<String, Boolean>>()
        override suspend fun preview(): DeletionPreview { calls += "preview"; failPreview?.let { throw it }; return preview }
        override suspend fun requestCode() { calls += "request"; failRequest?.let { throw it } }
        override suspend fun delete(code: String, confirmCompanyDeletion: Boolean) {
            calls += "delete"; deleteBodies += code to confirmCompanyDeletion; failDelete?.let { throw it }
        }
    }

    private var cleared = 0
    private fun controller(f: Fake, order: MutableList<String>? = null) = AccountController(f) { cleared++; order?.add("cleared") }

    @Test fun `happy path loading ready code sent deleting done, session cleared before done`() = runTest {
        val f = Fake().apply { preview = DeletionPreview(memberCount = 1, isOnlyAdmin = true) }
        val seen = mutableListOf<DeletePhase>()
        val c = controller(f)
        assertEquals(DeletePhase.LOADING, c.state.value.phase)
        c.load(); seen += c.state.value.phase
        assertEquals(f.preview, c.state.value.preview)
        c.sendCode(); seen += c.state.value.phase
        c.delete("123456", false); seen += c.state.value.phase
        assertEquals(listOf(DeletePhase.READY, DeletePhase.CODE_SENT, DeletePhase.DONE), seen)
        assertEquals(1, cleared)
        assertEquals(listOf("preview", "request", "delete"), f.calls)
        assertEquals(listOf("123456" to false), f.deleteBodies)
        assertNull(c.state.value.error)
    }

    @Test fun `busy phases are visible while the calls run`() = runTest {
        val phases = mutableListOf<DeletePhase>()
        lateinit var c: AccountController
        val port = object : AccountPort {
            override suspend fun preview(): DeletionPreview { phases += c.state.value.phase; return DeletionPreview() }
            override suspend fun requestCode() { phases += c.state.value.phase }
            override suspend fun delete(code: String, confirmCompanyDeletion: Boolean) { phases += c.state.value.phase }
        }
        c = AccountController(port) { phases += c.state.value.phase }
        c.load(); c.sendCode(); c.delete("000000", false)
        assertEquals(listOf(DeletePhase.LOADING, DeletePhase.SENDING_CODE, DeletePhase.DELETING, DeletePhase.DELETING), phases)
    }

    @Test fun `404 at any step is the unavailable message`() = runTest {
        val f = Fake().apply { failPreview = ApiException(404, "Cannot GET /api/account/delete/preview") }
        val c = controller(f)
        c.load()
        assertEquals(DeletePhase.UNAVAILABLE, c.state.value.phase)
        assertEquals("Account deletion is not available on this server yet. Contact support@shackyapps.in", c.state.value.error)
        // endpoint missing only on the request step
        val g = Fake().apply { failRequest = ApiException(404, "nope") }
        val d = controller(g); d.load(); d.sendCode()
        assertEquals(DeletePhase.UNAVAILABLE, d.state.value.phase); assertEquals(UNAVAILABLE_MESSAGE, d.state.value.error)
        assertEquals(0, cleared)
    }

    @Test fun `network failure on load can be retried`() = runTest {
        val f = Fake().apply { failPreview = IOException("offline") }
        val c = controller(f)
        c.load()
        assertEquals(DeletePhase.LOAD_FAILED, c.state.value.phase); assertTrue(c.state.value.error!!.startsWith("Cannot reach the server"))
        f.failPreview = null
        c.load()
        assertEquals(DeletePhase.READY, c.state.value.phase); assertNull(c.state.value.error)
    }

    @Test fun `a failed code request returns to ready with the message, and sendCode needs a loaded preview`() = runTest {
        val f = Fake()
        val c = controller(f)
        c.sendCode()
        assertTrue(f.calls.isEmpty())
        c.load(); f.failRequest = ApiException(429, "Too many requests. Try again in a minute.")
        c.sendCode()
        assertEquals(DeletePhase.READY, c.state.value.phase); assertEquals("Too many requests. Try again in a minute.", c.state.value.error)
        f.failRequest = IOException("x"); c.sendCode()
        assertEquals(DeletePhase.READY, c.state.value.phase)
        f.failRequest = ApiException(401, "Unauthorized"); c.sendCode()
        assertEquals(SESSION_MESSAGE, c.state.value.error)
        f.failRequest = ApiException(403, "Forbidden"); c.sendCode()
        assertEquals(NOT_ALLOWED_MESSAGE, c.state.value.error)
        f.failRequest = null; c.sendCode()
        assertEquals(DeletePhase.CODE_SENT, c.state.value.phase); assertNull(c.state.value.error)
    }

    @Test fun `wrong or expired code 400 and 401 stay on the code step and clear nothing`() = runTest {
        for (status in listOf(400, 401)) {
            val f = Fake()
            val c = controller(f)
            c.load(); c.sendCode()
            f.failDelete = ApiException(status, "Incorrect code.")
            c.delete("111111", false)
            assertEquals(DeletePhase.CODE_SENT, c.state.value.phase)
            assertEquals(BAD_CODE_MESSAGE, c.state.value.error)
            assertFalse(c.state.value.serverWantsCompanyConfirm)
        }
        assertEquals(0, cleared)
    }

    @Test fun `403 superadmin is its own message`() = runTest {
        val f = Fake(); val c = controller(f)
        c.load(); c.sendCode(); f.failDelete = ApiException(403, "Superadmin")
        c.delete("123456", false)
        assertEquals(NOT_ALLOWED_MESSAGE, c.state.value.error); assertEquals(DeletePhase.CODE_SENT, c.state.value.phase); assertEquals(0, cleared)
    }

    @Test fun `409 shows the company box even when the preview did not ask for it, then the retry sends the flag`() = runTest {
        val f = Fake().apply { preview = DeletionPreview(isOnlyAdmin = false, memberCount = 1) }
        val c = controller(f)
        c.load(); c.sendCode()
        assertFalse(c.state.value.needsCompanyConfirm)
        f.failDelete = ApiException(409, "confirmCompanyDeletion required")
        c.delete("123456", false)
        assertEquals(DeletePhase.CODE_SENT, c.state.value.phase)
        assertTrue(c.state.value.needsCompanyConfirm); assertEquals(COMPANY_CONFIRM_MESSAGE, c.state.value.error)
        // not ticked -> no request at all
        val before = f.calls.size
        c.delete("123456", false)
        assertEquals(before, f.calls.size); assertEquals(COMPANY_CONFIRM_MESSAGE, c.state.value.error)
        f.failDelete = null
        c.delete("123456", true)
        assertEquals(DeletePhase.DONE, c.state.value.phase)
        assertEquals(listOf("123456" to false, "123456" to true), f.deleteBodies)
        assertEquals(1, cleared)
    }

    @Test fun `only admin of a company with members must tick the box up front`() = runTest {
        val f = Fake().apply { preview = DeletionPreview(isOnlyAdmin = true, memberCount = 4) }
        val c = controller(f)
        c.load(); c.sendCode()
        assertTrue(c.state.value.needsCompanyConfirm)
        c.delete("123456", false)
        assertFalse(f.calls.contains("delete"))
        c.delete("123456", true)
        assertEquals(DeletePhase.DONE, c.state.value.phase)
    }

    @Test fun `a bad code shape never reaches the server`() = runTest {
        val f = Fake(); val c = controller(f)
        c.load(); c.sendCode()
        for (bad in listOf("", "12345", "1234567", "12345a", "12 345")) c.delete(bad, true)
        assertFalse(f.calls.contains("delete"))
        assertEquals("Enter the 6-digit code from the email.", c.state.value.error)
        c.delete("123456", false) // a well-formed code goes through
        assertTrue(f.calls.contains("delete"))
    }

    @Test fun `delete before a code was requested does nothing`() = runTest {
        val f = Fake(); val c = controller(f)
        c.load(); c.delete("123456", false)
        assertFalse(f.calls.contains("delete")); assertEquals(DeletePhase.READY, c.state.value.phase)
    }

    @Test fun `network failure while deleting keeps the code step and the session`() = runTest {
        val f = Fake(); val c = controller(f)
        c.load(); c.sendCode(); f.failDelete = IOException("reset")
        c.delete("123456", false)
        assertEquals(DeletePhase.CODE_SENT, c.state.value.phase); assertTrue(c.state.value.error!!.startsWith("Cannot reach the server")); assertEquals(0, cleared)
    }

    @Test fun `a failing local cleanup still ends in done`() = runTest {
        val c = AccountController(Fake()) { throw IllegalStateException("disk") }
        c.load(); c.sendCode(); c.delete("123456", false)
        assertEquals(DeletePhase.DONE, c.state.value.phase)
    }

    @Test fun `delete button enabling rules`() {
        assertTrue(canDeleteNow("123456", false, false))
        assertFalse(canDeleteNow("12345", false, true))
        assertFalse(canDeleteNow("12345a", false, true))
        assertFalse(canDeleteNow("123456", true, false))
        assertTrue(canDeleteNow("123456", true, true))
    }
}
