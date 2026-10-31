package app.streambird.android.ui

import app.streambird.android.ui.screens.fitInside
import org.junit.Assert.assertEquals
import org.junit.Test

class FitTest {
    @Test fun `preview fits inside its box with the stream aspect, letterboxed`() {
        // portrait 9:16 in a wide-short area: limited by height
        val p = fitInside(380f, 360f, 9f / 16f); assertEquals(360f, p.second, 0.01f); assertEquals(202.5f, p.first, 0.01f)
        // portrait in a tall narrow area: limited by width
        val q = fitInside(200f, 800f, 9f / 16f); assertEquals(200f, q.first, 0.01f); assertEquals(355.55f, q.second, 0.05f)
        // landscape 16:9
        val l = fitInside(400f, 300f, 16f / 9f); assertEquals(400f, l.first, 0.01f); assertEquals(225f, l.second, 0.01f)
        val l2 = fitInside(900f, 300f, 16f / 9f); assertEquals(300f, l2.second, 0.01f); assertEquals(533.33f, l2.first, 0.05f)
        assertEquals(0f to 0f, fitInside(0f, 10f, 1f))
        listOf(p, q, l, l2).forEach { assertEquals(it.first / it.second, if (it === l || it === l2) 16f / 9f else 9f / 16f, 0.001f) }
    }
}
