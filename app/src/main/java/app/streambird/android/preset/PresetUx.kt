package app.streambird.android.preset

import app.streambird.android.data.Preset
import app.streambird.android.data.PresetConfig
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.media.Ticker
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Pure rules of the presets USER EXPERIENCE (1.0.16, docs/UX-AUDIT-PRESETS.md): limit counter, name checks, duplicate naming, "edited" detection,
// "last used" wording, card warnings, error words. No Android, no network.

/** The server keeps at most this many presets per account (409 "You can keep up to 20 presets..."). */
const val MAX_PRESETS = 20

fun presetCountText(count: Int) = "$count of $MAX_PRESETS presets"

fun presetLimitReached(count: Int) = count >= MAX_PRESETS

const val LIMIT_REACHED_TEXT = "You have $MAX_PRESETS presets, the most an account can keep. Delete one to add another."

/** Under the "Save as preset" title: what a preset keeps and what it does not (the Customize sheet also holds controls a preset never stores). */
const val PRESET_STORES_TEXT = "A preset remembers channels, quality, shape, brand kit, ticker and visibility, so next time it is one tap."
const val PRESET_NOT_STORED_TEXT = "Not saved in a preset: title, logo choice, ads, thumbnail, date and time, camera."

/** The empty-state explanation on Home (no preset yet). */
const val PRESETS_EMPTY_TITLE = "Make your first preset"
const val PRESETS_EMPTY_BODY = "A preset saves your channels, quality and look so you can go live in one tap. Tap \"+ New preset\", or open Customize on the camera screen and choose \"Save as preset\"."

/** Shown under the Quick start card when the server has no presets feature (an older server). */
const val QUICK_START_OLD_SERVER_TEXT = "Saved presets are not available on this server yet. Quick start uses your current setup."

// ---- names ----

/** [blocking] stops Save (with the reason), [warning] only informs (the server allows two presets with one name). */
data class NameCheck(val blocking: String? = null, val warning: String? = null) {
    val ok get() = blocking == null
}

/** Name rules for "Save as preset" / Rename. [editingId] = the preset being renamed or updated: its own name is not a duplicate. */
fun checkPresetName(name: String, existing: Collection<Preset>, editingId: String? = null): NameCheck {
    val clean = name.trim()
    if (clean.isEmpty()) return NameCheck(blocking = "Give the preset a name to save it.")
    val clash = existing.any { it.id != editingId && it.name.trim().equals(clean, ignoreCase = true) }
    return if (clash) NameCheck(warning = "You already have a preset called \"$clean\". You can keep both, but they are easy to mix up.") else NameCheck()
}

/** "<name> copy", then "<name> copy 2", ... unique (case-insensitive) among [existingNames], at most [Preset.MAX_NAME] characters. */
fun duplicatePresetName(name: String, existingNames: Collection<String>): String {
    val taken = existingNames.map { it.trim().lowercase() }.toSet()
    fun make(suffix: String): String = name.trim().take((Preset.MAX_NAME - suffix.length).coerceAtLeast(1)).trimEnd() + suffix
    var candidate = make(" copy")
    var n = 2
    while (candidate.lowercase() in taken) { candidate = make(" copy $n"); n++ }
    return candidate
}

// ---- messages ----

fun deletedText(name: String) = "Deleted \"$name\""

/** What a failed write says. 404 / 401 as before; 422 and 429 get a plain hint (422 keeps the server's own words first); everything else keeps the server's own words. */
fun presetWriteError(op: String, status: Int, serverMessage: String): String {
    val prefix = when (op) { "delete" -> "Could not delete the preset"; "rename" -> "Could not rename the preset"; "duplicate" -> "Could not duplicate the preset"; else -> "Could not save the preset" }
    return when {
        status == 404 && op == "update" -> "That preset no longer exists (it may have been deleted on another device). Save it again as a new preset."
        status == 404 && op == "rename" -> "That preset no longer exists (it may have been deleted on another device)."
        status == 404 && op == "delete" -> "That preset no longer exists."
        status == 401 -> "Your session expired. Sign in again."
        status == 422 -> "$prefix: ${serverMessage.trimEnd('.')}. A channel or brand kit in it may no longer be on your account; open the preset, check them and save again."
        status == 429 -> "Too many requests in a short time. Wait a minute and try again."
        else -> "$prefix: $serverMessage"
    }
}

// ---- "last used" ----

/** "Never used", "Used today", "Used yesterday", "Used 3 days ago", "Used 2 weeks ago", "Used 5 months ago"; null when the time cannot be read. */
fun lastUsedLabel(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (iso.isNullOrBlank()) return "Never used"
    val then = runCatching { Instant.parse(iso.trim()) }.getOrNull() ?: return null
    val days = ChronoUnit.DAYS.between(then.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0 -> "Used today"
        days == 1L -> "Used yesterday"
        days < 14 -> "Used $days days ago"
        days < 60 -> "Used ${days / 7} weeks ago"
        days < 365 -> "Used ${days / 30} months ago"
        else -> "Used over a year ago"
    }
}

// ---- warnings and "edited" ----

/**
 * What would be dropped or lowered if this preset were opened now (channel disconnected, plan allows fewer destinations, quality above the plan,
 * deleted kit): the same notes the camera screen shows, but BEFORE the host taps (on the card).
 */
fun presetWarnings(cfg: PresetConfig, limits: SetupLimits): List<String> =
    applyPresetConfig(cfg, SetupChoices(emptySet(), StreamTier.P540, StreamOrientation.PORTRAIT, null, ""), limits).notes

/**
 * The parts of the open setup that differ from the saved preset (empty = nothing to save), compared with what the preset would apply TODAY
 * (so a channel the account lost is not reported as an edit). Names: "Channels", "Quality", "Shape", "Brand kit", "Ticker", "Visibility".
 */
fun presetChanges(current: SetupChoices, saved: PresetConfig, limits: SetupLimits): List<String> {
    val base = applyPresetConfig(saved, current, limits).choices
    return buildList {
        if (current.selected.toSet() != base.selected.toSet()) add("Channels")
        if (current.tier != base.tier) add("Quality")
        if (current.orientation != base.orientation) add("Shape")
        if (current.brandKitId != base.brandKitId) add("Brand kit")
        if (Ticker.sanitize(current.tickerText) != Ticker.sanitize(base.tickerText)) add("Ticker")
        if (visibilityFromWire(current.visibility) != visibilityFromWire(base.visibility)) add("Visibility")
    }
}

/** "Unsaved changes: Quality, Ticker" or "Matches the saved preset". */
fun changesText(changes: List<String>) = if (changes.isEmpty()) "Matches the saved preset" else "Unsaved changes: ${changes.joinToString(", ")}"
