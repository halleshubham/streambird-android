package app.streambird.android.stream

import app.streambird.android.data.ThumbnailResult
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLinksTest {
    @Test fun `share text`() {
        assertEquals("", buildShareText(emptyList()))
        assertEquals("Watch me live: https://streambird.app/stream/abc", buildShareText(listOf(" https://streambird.app/stream/abc ")))
        assertEquals("Watch me live:\nhttps://a/1\nhttps://b/2", buildShareText(listOf("https://a/1", "", "https://b/2", "https://a/1")))
    }

    @Test fun `permanent link first, destination watch url as fallback`() {
        val links = mapOf("c1" to "https://streambird.app/stream/yt", "c2" to null, "c3" to " ")
        val watch = listOf("c1" to "https://youtube.com/watch?v=1", "c2" to "https://facebook.com/v/2", "c3" to null, "c4" to "https://x/4")
        assertEquals(listOf("https://streambird.app/stream/yt", "https://facebook.com/v/2"), resolveLiveLinks(listOf("c1", "c2", "c3"), links, watch))
        assertEquals(emptyList<String>(), resolveLiveLinks(listOf("c3"), links, watch))
        assertEquals(listOf("https://x/4"), resolveLiveLinks(listOf("c4"), emptyMap(), watch))
    }

    @Test fun `thumbnail notices`() {
        assertEquals(NoticeKind.SUCCESS, thumbnailNotice(ThumbnailResult(hasThumbnail = true)).kind)
        val w = thumbnailNotice(ThumbnailResult(true, null, listOf("YouTube needs a verified channel for custom thumbnails.", "Facebook best effort.")))
        assertEquals(NoticeKind.WARNING, w.kind)
        assertEquals("Thumbnail saved, with notes:\n- YouTube needs a verified channel for custom thumbnails.\n- Facebook best effort.", w.text)
        val e = thumbnailFailure("The image is too small -- it must be at least 640px wide (1280x720 is recommended).")
        assertEquals(NoticeKind.ERROR, e.kind)
        assertEquals("Thumbnail was not set: The image is too small -- it must be at least 640px wide (1280x720 is recommended).. Your stream is not affected.", e.text)
    }
}
