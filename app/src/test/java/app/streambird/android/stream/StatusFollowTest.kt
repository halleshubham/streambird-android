package app.streambird.android.stream

import app.streambird.android.data.ApiClient
import app.streambird.android.data.OriginInterceptor
import app.streambird.android.data.StreamListItem
import app.streambird.android.data.StreamStatusDestination
import app.streambird.android.data.recentStreams
import app.streambird.android.stream.StatusFollow.Server
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class StatusFollowTest {
    @Test fun `server status interpretation and stop rule`() {
        assertEquals(Server.LIVE, StatusFollow.interpret("live")); assertEquals(Server.ENDED, StatusFollow.interpret("ended"))
        assertEquals(Server.FAILED, StatusFollow.interpret("failed")); assertEquals(Server.OTHER, StatusFollow.interpret("scheduled")); assertEquals(Server.OTHER, StatusFollow.interpret(null))
        assertFalse(StatusFollow.mustStop("live"))
        listOf("ended", "failed", "scheduled", null).forEach { assertTrue(StatusFollow.mustStop(it)) }
        assertTrue(StatusFollow.gone(404)); assertFalse(StatusFollow.gone(500))
    }

    @Test fun `end reasons`() {
        assertEquals("Stream ended.", StatusFollow.endMessage("ended", byUs = true))
        assertEquals("Stopped sending, but the server did not confirm the end.", StatusFollow.endMessage(null, byUs = true, confirmed = false))
        assertTrue(StatusFollow.endMessage("ended", byUs = false).contains("ended elsewhere"))
        assertTrue(StatusFollow.endMessage("failed", byUs = false).contains("stopped by the server"))
    }

    private fun dest(status: String, platform: String? = "live") = StreamStatusDestination("d", "c", status, platform)

    @Test fun `destination rows never show a stale live after the stream is over`() {
        assertEquals("ended", StatusFollow.destinationStatus("ended", "live"))
        assertEquals("failed", StatusFollow.destinationStatus("ended", "failed"))
        assertEquals("live", StatusFollow.destinationStatus("live", "live"))
        assertEquals("live", StatusFollow.destinationStatus("scheduled", "live"))     // unknown state: leave as reported
        val f = StatusFollow.finalDestinations("ended", listOf(dest("live"), dest("failed")))
        assertEquals(listOf("ended", "failed"), f.map { it.status }); assertEquals(listOf(null, null), f.map { it.platformStatus })
        assertEquals("live", StatusFollow.finalDestinations("live", listOf(dest("live"))).single().platformStatus)
    }

    private fun item(id: String, status: String) = StreamListItem(id, "t$id", status)

    @Test fun `stale stream selection excludes the active one, dismissed ones and non-live`() {
        val items = listOf(item("a", "live"), item("b", "ended"), item("c", "LIVE"), item("d", "scheduled"), item("e", "live"))
        assertEquals(listOf("a", "c", "e"), staleLiveStreams(items, null, emptySet()).map { it.id })
        assertEquals(listOf("c", "e"), staleLiveStreams(items, "a", emptySet()).map { it.id })
        assertEquals(listOf("c"), staleLiveStreams(items, "a", setOf("e")).map { it.id })
    }

    @Test fun `started time is formatted in the given zone, blank when unparsable`() {
        assertEquals("31 Oct, 22:43", formatStarted("2026-10-31T17:13:00.000Z", ZoneId.of("Asia/Kolkata")))
        assertEquals("", formatStarted(null)); assertEquals("", formatStarted("garbage"))
    }

    @Test fun `GET streams parses the list shape`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = { server.url("/").toString().trimEnd('/') }
            val api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[{"id":"s1","title":"ghj","status":"live","scheduledAt":null,"startedAt":"2026-10-31T17:13:00.000Z","endedAt":null,"cancelledAt":null,"isScheduledEvent":false,"createdAt":"2026-10-31T17:12:50.000Z","destinationsSummary":[{"platform":"youtube","status":"live"}]}],"total":1,"limit":20,"offset":0}"""))
            val l = api.recentStreams()
            assertEquals("ghj", l.single().title); assertEquals("live", l.single().status)
            assertEquals("/api/streams?limit=20", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
