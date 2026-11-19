package app.streambird.android.ui.screens

import app.streambird.android.media.NameBanner
import app.streambird.android.media.NameBannerState

/**
 * The logic of the Live screen's "Name banner" row, apart from the Compose code so the JVM can test it: what the button says, what the dialog's buttons
 * say, and what Show / Hide send to the controller (drafts are the raw text fields; the controller cleans them again, so what is shown on air is what
 * [NameBanner.clean] makes of it).
 */
object BannerEditor {
    const val NAME_ROW = "Name banner"

    /** The row's button: "Name banner (on)" while the banner is on air. */
    fun rowLabel(s: NameBannerState) = if (s.visible) "$NAME_ROW (on)" else NAME_ROW

    /** The dialog's main button: "Update" while the banner is on air (new text replaces the old on the picture), else "Show". */
    fun confirmLabel(s: NameBannerState) = if (s.visible) "Update" else "Show"
    const val HIDE_LABEL = "Hide"

    /** What the dialog's fields start with: the texts last used (kept while hidden, so showing it again is one tap). */
    fun draftName(s: NameBannerState) = s.name
    fun draftTitle(s: NameBannerState) = s.title.orEmpty()

    /** Field limits (the text boxes cut at these). */
    fun limitName(raw: String) = raw.take(NameBanner.NAME_MAX)
    fun limitTitle(raw: String) = raw.take(NameBanner.TITLE_MAX)

    sealed interface Result {
        /** Send these to the controller: texts as typed, and whether the banner shows. */
        data class Send(val name: String, val title: String, val show: Boolean) : Result
        data class Refused(val message: String) : Result
    }

    /** Show / Update: needs a name (a banner without one has nothing to show). */
    fun show(draftName: String, draftTitle: String): Result =
        if (NameBanner.cleanName(draftName).isEmpty()) Result.Refused("Type a name first.") else Result.Send(draftName, draftTitle, true)

    /** Hide: keeps what was typed (so Show brings it back), switches the banner off. */
    fun hide(draftName: String, draftTitle: String): Result = Result.Send(draftName, draftTitle, false)
}
