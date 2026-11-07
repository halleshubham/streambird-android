package app.streambird.android.preset

import app.streambird.android.data.PlatformConnection
import app.streambird.android.data.Preset
import app.streambird.android.data.PresetConfig
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.media.Ticker

// Pure rules of the presets feature (no Android, no network): preset <-> stream setup mapping, summary text, default card, ordering.

/** The part of the stream setup a preset covers. */
data class SetupChoices(
    val selected: Set<String>,
    val tier: StreamTier,
    val orientation: StreamOrientation,
    /** null = automatic. */
    val brandKitId: String?,
    /** Blank = ticker off. */
    val tickerText: String,
)

/** What the account can do right now (GET /plans/me + connections + brand kits). [knownKitIds] null = kits not loaded (yet) or not available. */
data class SetupLimits(
    val connectionIds: Set<String>,
    val maxDestinations: Int,
    val allowedTiers: List<StreamTier>,
    val brandAllowed: Boolean,
    val knownKitIds: Set<String>?,
)

data class AppliedPreset(val choices: SetupChoices, val notes: List<String>)

/** Quality on the wire: the web's plan resolution names (hd = 720p, fhd = 1080p; 540p is the app's own "sd"). */
fun tierToWire(t: StreamTier): String = when (t) {
    StreamTier.P540 -> "sd"
    StreamTier.P720 -> "hd"
    StreamTier.P1080 -> "fhd"
}

/** Tolerant: the wire names, the app's labels ("720p") and the enum names, any case. Unknown -> null (the current quality stays). */
fun tierFromWire(s: String?): StreamTier? = when (s?.trim()?.lowercase()) {
    "sd", "540p", "p540", "360p" -> StreamTier.P540
    "hd", "720p", "p720" -> StreamTier.P720
    "fhd", "1080p", "p1080", "qhd", "1440p" -> StreamTier.P1080
    else -> null
}

fun orientationFromWire(s: String?): StreamOrientation? = StreamOrientation.entries.firstOrNull { it.wire.equals(s?.trim(), ignoreCase = true) }

/** The current setup as a preset config (what "Save preset" sends). Quality, orientation and ticker are always written; destinations in the ticked order. */
fun choicesToConfig(c: SetupChoices): PresetConfig {
    val text = Ticker.sanitize(c.tickerText)
    return PresetConfig(
        orientation = c.orientation.wire,
        destinationIds = c.selected.toList(),
        brandKitId = c.brandKitId,
        quality = tierToWire(c.tier),
        tickerEnabled = text.isNotEmpty(),
        tickerText = text.ifEmpty { null },
    )
}

/**
 * Preset -> stream setup. Fields the preset does not have keep their current value. Whatever the account cannot do any more
 * (a disconnected channel, a quality above the plan, more destinations than the plan allows, a deleted kit) is dropped or lowered, and a note says so.
 */
fun applyPresetConfig(cfg: PresetConfig, current: SetupChoices, limits: SetupLimits): AppliedPreset {
    val notes = mutableListOf<String>()

    var selected = current.selected
    cfg.destinationIds?.let { wanted ->
        val ok = wanted.filter { it in limits.connectionIds }.distinct()
        if (ok.size < wanted.distinct().size) notes += "Some channels of this preset are no longer connected."
        val capped = ok.take(limits.maxDestinations.coerceAtLeast(0))
        if (capped.size < ok.size) notes += "Your plan allows ${limits.maxDestinations} destination${if (limits.maxDestinations == 1) "" else "s"} at a time; the rest were left out."
        selected = capped.toCollection(LinkedHashSet())
    }

    var tier = current.tier
    tierFromWire(cfg.quality)?.let { want ->
        tier = if (want in limits.allowedTiers) want
        else limits.allowedTiers.lastOrNull { it.longSide <= want.longSide } ?: limits.allowedTiers.firstOrNull() ?: current.tier
        if (tier != want) notes += "${want.label} is above your plan; using ${tier.label}."
    }

    val orientation = orientationFromWire(cfg.orientation) ?: current.orientation

    var kit = current.brandKitId
    if (cfg != PresetConfig()) {
        // A preset always decides the kit: its own kit, or automatic when it has none (an empty config changes nothing).
        kit = cfg.brandKitId
        if (kit != null && limits.knownKitIds != null && kit !in limits.knownKitIds) { kit = null; notes += "The brand kit of this preset was deleted; using automatic." }
        if (!limits.brandAllowed) kit = null
    }

    val ticker = when (cfg.tickerEnabled) {
        true -> Ticker.sanitize(cfg.tickerText ?: current.tickerText)
        false -> ""
        null -> if (cfg.tickerText != null) Ticker.sanitize(cfg.tickerText) else current.tickerText
    }
    return AppliedPreset(SetupChoices(selected, tier, orientation, kit, ticker), notes)
}

/** Name used on chips and in summaries: the platform name, or "Platform - label" when two connections share a platform. */
fun connectionName(c: PlatformConnection, all: List<PlatformConnection>): String {
    val p = platformDisplayName(c.platform)
    return if (all.count { it.platform == c.platform } > 1 && c.label.isNotBlank()) "$p (${c.label})" else p
}

fun platformDisplayName(p: String) = when (p) {
    "youtube" -> "YouTube"
    "facebook" -> "Facebook"
    "twitch" -> "Twitch"
    "linkedin" -> "LinkedIn"
    else -> p.replaceFirstChar { it.uppercase() }
}

/** "Acme brand · 720p · Ticker off" (the line under Go live). [brandName] null = nothing to show (Free plan). */
fun setupLine(brandName: String?, tier: StreamTier, tickerOn: Boolean): String =
    listOfNotNull(brandName?.let { "$it brand" }, tier.label, if (tickerOn) "Ticker on" else "Ticker off").joinToString(SEP)

/**
 * "YouTube + Facebook · Acme brand · Ticker on · 720p" (the one-line summary of a card). Destinations the account no longer has are skipped.
 * [kitName] resolves a kit id (null = unknown kit); a preset without a kit says "Auto brand"; no brand part at all when [brandAllowed] is false.
 * 16:9 is mentioned only for landscape (portrait is the app's usual shape).
 */
fun presetSummary(cfg: PresetConfig, connections: List<PlatformConnection>, kitName: (String) -> String?, brandAllowed: Boolean): String {
    val dests = cfg.destinationIds.orEmpty().mapNotNull { id -> connections.firstOrNull { it.id == id }?.let { connectionName(it, connections) } }
    val parts = mutableListOf<String>()
    parts += if (dests.isEmpty()) "No destination" else dests.joinToString(" + ")
    if (brandAllowed) parts += cfg.brandKitId?.let { id -> (kitName(id) ?: "Brand kit").let { "$it brand" } } ?: "Auto brand"
    parts += if (cfg.tickerEnabled == true) "Ticker on" else "Ticker off"
    tierFromWire(cfg.quality)?.let { parts += it.label }
    if (orientationFromWire(cfg.orientation)?.isLandscape == true) parts += "16:9"
    return parts.joinToString(SEP)
}

const val SEP = " · "

/**
 * The name proposed in the "Save as preset" field so the Save button is usable at once: the ticked channels ("YouTube + Facebook"), else "My preset";
 * made unique among [existing] names by a number ("YouTube 2"), at most [Preset.MAX_NAME] characters.
 */
fun suggestPresetName(selected: Collection<String>, connections: List<PlatformConnection>, existing: Collection<String>): String {
    val names = selected.mapNotNull { id -> connections.firstOrNull { it.id == id } }.map { platformDisplayName(it.platform) }.distinct()
    val base = (if (names.isEmpty()) "My preset" else names.take(3).joinToString(" + ")).take(Preset.MAX_NAME - 4)
    val taken = existing.map { it.trim().lowercase() }.toSet()
    if (base.lowercase() !in taken) return base
    var n = 2
    while ("$base $n".lowercase() in taken) n++
    return "$base $n"
}

const val IMPLICIT_NAME = "Quick start"

/**
 * The single card shown when the server has no presets (404 / offline) or no preset yet: the setup as it is today.
 * Destinations: the ones ticked before, else the first channels the plan allows.
 */
fun implicitDefaultPreset(current: SetupChoices, connections: List<PlatformConnection>, maxDestinations: Int, brandAllowed: Boolean): Preset {
    val known = connections.map { it.id }.toSet()
    val ticked = current.selected.filter { it in known }
    val dests = ticked.ifEmpty { connections.map { it.id } }.take(maxDestinations.coerceAtLeast(1))
    val c = choicesToConfig(current.copy(selected = dests.toCollection(LinkedHashSet()), brandKitId = if (brandAllowed) current.brandKitId else null))
    return Preset(id = "", name = IMPLICIT_NAME, config = c, lastUsedAt = null)
}

/** Most recently used first (ISO-8601 strings sort chronologically); never-used last, keeping the server order among equals. */
fun orderForHome(items: List<Preset>): List<Preset> =
    items.withIndex().sortedWith(compareByDescending<IndexedValue<Preset>> { it.value.lastUsedAt ?: "" }.thenBy { it.index }).map { it.value }
