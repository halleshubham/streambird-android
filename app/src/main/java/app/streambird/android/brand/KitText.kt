package app.streambird.android.brand

import app.streambird.android.data.BrandKit

/**
 * A brand kit's default `title` and `description` (1.0.9). They are DEFAULTS for streams that use the kit: a stream's own non-blank value wins, a
 * blank one falls back to the kit's (the server does this on POST /streams; the app also shows and sends the resolved title so what the host sees is
 * what goes on air). Pure Kotlin, JVM-tested.
 */
object KitText {
    /** Server limit of the kit title. */
    const val TITLE_MAX = 100

    /** Server limit of the kit description = the stream description limit (CreateStreamDto.description, @MaxLength(5000)). */
    const val DESCRIPTION_MAX = 5000

    /** The stream title limit (CreateStreamDto.title). */
    const val STREAM_TITLE_MAX = 200

    const val HINT = "Used for new streams that don't have their own title or description. You can change it on each stream."

    /** Trimmed, or null when blank (the server stores blank as cleared). */
    fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    fun cleanTitle(s: String?): String? = clean(s)?.take(TITLE_MAX)?.trimEnd()
    fun cleanDescription(s: String?): String? = clean(s)?.take(DESCRIPTION_MAX)?.trimEnd()

    /** What a PATCH must say about one text field. */
    sealed interface Change {
        /** Omit the field (unchanged; an older server never sees it). */
        data object Unchanged : Change
        data class Set(val value: String) : Change

        /** Explicit JSON null: clears it. */
        data object Clear : Change
    }

    private fun change(original: String?, draft: String?, clean: (String?) -> String?): Change {
        val was = clean(original)
        val now = clean(draft)
        return when {
            was == now -> Change.Unchanged
            now == null -> Change.Clear
            else -> Change.Set(now)
        }
    }

    fun titleChange(original: String?, draft: String?): Change = change(original, draft, ::cleanTitle)
    fun descriptionChange(original: String?, draft: String?): Change = change(original, draft, ::cleanDescription)

    // ---- the stream's title in Setup / Customize ----

    /**
     * The title field of the stream: [text] is what is shown, [edited] says the host typed (a typed value, even an emptied field, always wins and is never
     * overwritten), [fromKit] is the name of the kit the text was taken from (null = not from a kit).
     */
    data class TitleState(val text: String = "", val edited: Boolean = false, val fromKit: String? = null)

    /**
     * The kit in use changed (or loaded): while the host has not typed, the title follows the kit's default (blank when the kit has none); once the host
     * typed, nothing changes.
     */
    fun reconcile(state: TitleState, kit: BrandKit?): TitleState {
        if (state.edited) return state
        val def = cleanTitle(kit?.title)
        return if (def != null) TitleState(def, edited = false, fromKit = kit?.name) else TitleState("", edited = false, fromKit = null)
    }

    /** The host typed [newText]. Same text as shown = no change (opening a dialog and pressing Done does not count as typing). */
    fun typed(state: TitleState, newText: String): TitleState {
        val text = newText.take(STREAM_TITLE_MAX)
        return if (text == state.text) state else TitleState(text, edited = true, fromKit = null)
    }

    /** The title to create the stream with: the field, else the kit's default, else "" (the caller substitutes its own fallback). */
    fun finalTitle(text: String, kit: BrandKit?): String = clean(text) ?: cleanTitle(kit?.title) ?: ""

    /** The line under the title field: where the shown text comes from. */
    fun titleNote(state: TitleState, kit: BrandKit?): String? = when {
        state.fromKit != null -> "From brand kit ${state.fromKit}"
        state.edited && state.text.isBlank() && cleanTitle(kit?.title) != null -> "Left blank: the title of brand kit ${kit?.name} is used."
        else -> null
    }

    /** Setup shows one line when the kit in use has a default description, because the server applies it to a stream created without its own (the app sends none). */
    fun descriptionNote(kit: BrandKit?): String? = if (cleanDescription(kit?.description) != null) "Description: from brand kit ${kit?.name}" else null
}
