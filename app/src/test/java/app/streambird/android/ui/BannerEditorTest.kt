package app.streambird.android.ui

import app.streambird.android.media.NameBannerState
import app.streambird.android.ui.screens.BannerEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state logic of the Live screen's "Name banner" row (labels, what Show and Hide send, limits). */
class BannerEditorTest {
    @Test fun `labels follow whether the banner is on air`() {
        assertEquals("Name banner", BannerEditor.rowLabel(NameBannerState()))
        assertEquals("Name banner", BannerEditor.rowLabel(NameBannerState("Jane", null, false)))
        assertEquals("Name banner (on)", BannerEditor.rowLabel(NameBannerState("Jane", null, true)))
        assertEquals("Name banner", BannerEditor.rowLabel(NameBannerState("", null, true)))          // switched on but nothing to show
        assertEquals("Show", BannerEditor.confirmLabel(NameBannerState("Jane", null, false)))
        assertEquals("Update", BannerEditor.confirmLabel(NameBannerState("Jane", null, true)))
        assertEquals("Hide", BannerEditor.HIDE_LABEL)
    }

    @Test fun `the dialog starts with the last texts`() {
        val s = NameBannerState("Jane", "Host", false)
        assertEquals("Jane", BannerEditor.draftName(s)); assertEquals("Host", BannerEditor.draftTitle(s))
        assertEquals("", BannerEditor.draftTitle(NameBannerState("Jane", null, true)))
    }

    @Test fun `show needs a name and sends the typed texts`() {
        assertEquals(BannerEditor.Result.Send("Jane", "Host", true), BannerEditor.show("Jane", "Host"))
        assertEquals(BannerEditor.Result.Refused("Type a name first."), BannerEditor.show("   ", "Host"))
        assertEquals(BannerEditor.Result.Refused("Type a name first."), BannerEditor.show("‮\n", ""))     // nothing left after cleaning
    }

    @Test fun `hide always works and keeps the texts so show brings them back`() {
        assertEquals(BannerEditor.Result.Send("Jane", "Host", false), BannerEditor.hide("Jane", "Host"))
        assertEquals(BannerEditor.Result.Send("", "", false), BannerEditor.hide("", ""))
    }

    @Test fun `the text boxes stop at the caps`() {
        assertEquals(80, BannerEditor.limitName("n".repeat(200)).length)
        assertEquals(120, BannerEditor.limitTitle("t".repeat(200)).length)
        assertTrue(BannerEditor.limitName("short") == "short")
    }

    @Test fun `what the host sends through the state patch matches what is shown on air`() {
        val s = NameBannerState().patched("  Jane‮  Doe ", true, " Host \n of the show", true)
        assertEquals(NameBannerState("Jane Doe", "Host of the show", true), s)
        assertTrue(s.visible)
        val hidden = s.patched("Jane Doe", true, "Host of the show", false)
        assertEquals(false, hidden.visible); assertEquals("Jane Doe", hidden.name)
    }
}
