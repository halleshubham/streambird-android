package app.streambird.android.brand

import app.streambird.android.ads.AdPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadStateTest {
    private val base = UploadState(UploadKeys.logo(BrandOrientation.LANDSCAPE), "logo")

    @Test fun `unknown size is indeterminate`() {
        assertNull(base.fraction); assertNull(base.percent)
        assertEquals("Uploading...", base.visibleText)
        assertEquals("Uploading", base.announcement)
    }

    @Test fun `known size gives fraction and percent, clamped`() {
        val s = base.withName("logo.png").withProgress(250, 1000)
        assertEquals(0.25f, s.fraction!!, 0.0001f)
        assertEquals(25, s.percent)
        assertEquals("Uploading logo.png 25%", s.visibleText)
        assertEquals(1f, base.withProgress(2000, 1000).fraction!!, 0f)
        assertEquals(0f, base.withProgress(-5, 1000).fraction!!, 0f)
    }

    @Test fun `TalkBack text has the file name and never the percentage`() {
        val s = base.withName("summer sale.gif").withProgress(10, 100)
        assertEquals("Uploading summer sale.gif", s.announcement)
        assertFalse(s.announcement.contains("%"))
        assertEquals("Uploading", base.withName("  ").announcement)
    }

    @Test fun `progress is reported first, on each new percent and at the end only`() {
        val total = 1000L
        val reported = mutableListOf<Long>()
        var last = -1
        for (sent in 0..total step 5) {
            if (shouldReportProgress(last, sent, total)) { last = (sent * 100 / total).toInt(); reported += sent }
        }
        assertEquals(0L, reported.first()); assertEquals(total, reported.last())
        assertTrue(reported.size <= 101)
        assertTrue(shouldReportProgress(-1, 0, 0)); assertFalse(shouldReportProgress(0, 10, 0))
        assertFalse(shouldReportProgress(50, 505, 1000))
        assertTrue(shouldReportProgress(50, 510, 1000))
    }

    @Test fun `slot keys separate sets, placements and add-another`() {
        val a = UploadKeys.ad(BrandOrientation.LANDSCAPE, AdPlacement.L_SHAPE, 0)
        assertTrue(a != UploadKeys.ad(BrandOrientation.PORTRAIT, AdPlacement.L_SHAPE, 0))
        assertTrue(a != UploadKeys.ad(BrandOrientation.LANDSCAPE, AdPlacement.LOWER_THIRD, 0))
        val add = UploadKeys.ad(BrandOrientation.LANDSCAPE, AdPlacement.L_SHAPE, null)
        assertTrue(UploadKeys.isAdd(add)); assertFalse(UploadKeys.isAdd(a))
        assertTrue(UploadKeys.logo(BrandOrientation.LANDSCAPE) != UploadKeys.logo(BrandOrientation.PORTRAIT))
    }
}
