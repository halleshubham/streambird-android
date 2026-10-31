package app.streambird.android.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPlanTest {
    @Test fun `landscape photo is fitted into 1280x720 and never enlarged`() {
        assertEquals(ThumbnailPlan.Size(1280, 720), ThumbnailPlan.targetSize(4000, 2250, portrait = false))
        assertEquals(ThumbnailPlan.Size(960, 720), ThumbnailPlan.targetSize(4000, 3000, portrait = false))
        assertEquals(ThumbnailPlan.Size(1000, 700), ThumbnailPlan.targetSize(1000, 700, portrait = false))
    }

    @Test fun `portrait stream fits 720x1280`() {
        assertEquals(ThumbnailPlan.Size(720, 1280), ThumbnailPlan.targetSize(3000, 5333, portrait = true))
        assertEquals(ThumbnailPlan.Size(720, 405), ThumbnailPlan.targetSize(4000, 2250, portrait = true))
    }

    @Test fun `never narrower than the backend minimum of 640 px`() {
        // a tall photo for a landscape stream: fit-into-box would give 405 px wide, which the backend refuses
        val t = ThumbnailPlan.targetSize(3000, 4000, portrait = false)!!
        assertEquals(640, t.width); assertEquals(853, t.height)
        // tiny sources are enlarged to 640 wide
        assertEquals(ThumbnailPlan.Size(640, 427), ThumbnailPlan.targetSize(300, 200, portrait = false))
        // absurd shapes and empty sources are refused
        assertNull(ThumbnailPlan.targetSize(100, 2000, portrait = false))
        assertNull(ThumbnailPlan.targetSize(0, 10, portrait = false))
    }

    @Test fun `sample size keeps at least the target resolution`() {
        val t = ThumbnailPlan.Size(1280, 720)
        assertEquals(1, ThumbnailPlan.sampleSize(1500, 900, t))
        assertEquals(2, ThumbnailPlan.sampleSize(2560, 1440, t))
        assertEquals(2, ThumbnailPlan.sampleSize(4000, 3000, t))   // 4 would decode 1000x750, below 1280 wide
    }

    @Test fun `lowers quality first, then size, until under the budget`() {
        val calls = ArrayList<Triple<Int, Int, Int>>()
        // fake encoder: size shrinks with quality and area
        val r = ThumbnailPlan.encodeWithinLimit(ThumbnailPlan.Size(1280, 720)) { w, h, q ->
            calls.add(Triple(w, h, q))
            ByteArray(if (q >= 82) 3_000_000 else 1_000_000)
        }!!
        assertEquals(74, r.quality); assertEquals(1280, r.width)
        assertEquals(listOf(90, 82, 74), calls.map { it.third })

        calls.clear()
        val r2 = ThumbnailPlan.encodeWithinLimit(ThumbnailPlan.Size(1280, 720)) { w, h, q ->
            calls.add(Triple(w, h, q)); ByteArray(if (w >= 1280) 5_000_000 else 100_000)
        }!!
        assertEquals(ThumbnailPlan.QUALITIES.size + 1, calls.size)
        assertEquals(1024, r2.width); assertEquals(576, r2.height); assertEquals(90, r2.quality)
    }

    @Test fun `gives up only when even the smallest allowed size is too big`() {
        assertNull(ThumbnailPlan.encodeWithinLimit(ThumbnailPlan.Size(1280, 720)) { _, _, _ -> ByteArray(ThumbnailPlan.MAX_BYTES + 1) })
        assertNotNull(ThumbnailPlan.encodeWithinLimit(ThumbnailPlan.Size(640, 360)) { _, _, _ -> ByteArray(10) })
        assertTrue(ThumbnailPlan.BYTE_BUDGET < ThumbnailPlan.MAX_BYTES)
    }
}
