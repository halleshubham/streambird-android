package app.streambird.android.remote

import app.streambird.android.ads.AD_EVERY_SEC_MAX
import app.streambird.android.ads.AD_EVERY_SEC_MIN
import app.streambird.android.ads.AD_SHOW_SEC_MAX
import app.streambird.android.ads.AD_SHOW_SEC_MIN
import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.clock.Clock
import app.streambird.android.clock.ClockHours
import app.streambird.android.clock.ClockPosition
import app.streambird.android.clock.ClockSettings
import app.streambird.android.clock.ClockSize
import app.streambird.android.clock.ClockStyle
import app.streambird.android.media.Ticker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

const val NOT_SUPPORTED = "not supported on the phone app"

/** What a whitelisted command asks for, already validated (ranges, enums, sizes). Applying it is [RemoteApplier]'s job. */
sealed class RemoteAction {
    data class AdsManual(val placement: AdPlacement, val mode: AdManual?) : RemoteAction()
    data class AdsEnabled(val placement: AdPlacement, val on: Boolean) : RemoteAction()
    data class AdsShrink(val on: Boolean) : RemoteAction()
    data class AdsSchedule(val placement: AdPlacement, val schedule: AdSchedule) : RemoteAction()
    data class TickerSet(val text: String?, val on: Boolean) : RemoteAction()
    data class ClockSet(val patch: ClockPatch) : RemoteAction()
    /** A whitelisted type the phone app has no equivalent for (logo.set, kit.apply, layout.set, lowerThird.set, wallpaper.dim): acked as not supported. */
    data class Unsupported(val why: String = NOT_SUPPORTED) : RemoteAction()
}

/** `clock.set` fields; a field the command does not carry stays as it is on the phone. [zoneGiven] tells "no timezone key" from an explicit null (= the phone's own zone). */
data class ClockPatch(
    val enabled: Boolean? = null, val position: ClockPosition? = null, val hours: ClockHours? = null, val showDate: Boolean? = null,
    val showSeconds: Boolean? = null, val zoneGiven: Boolean = false, val zone: String? = null, val size: ClockSize? = null,
    val style: ClockStyle? = null, val alternate: Boolean? = null,
) {
    fun applyTo(c: ClockSettings): ClockSettings = c.copy(
        enabled = enabled ?: c.enabled, position = position ?: c.position, hours = hours ?: c.hours, showDate = showDate ?: c.showDate,
        showSeconds = showSeconds ?: c.showSeconds, timezone = if (zoneGiven) zone else c.timezone, size = size ?: c.size,
        style = style ?: c.style, alternate = alternate ?: c.alternate,
    ).normalized()
}

/** One command as the server relays it. [atMs] = the `at` timestamp in epoch milliseconds. */
data class RemoteCommand(val seq: Long, val id: String, val type: String, val atMs: Long, val action: RemoteAction)

sealed class ParsedCommand {
    data class Ok(val command: RemoteCommand) : ParsedCommand()
    /** Refused before anything is applied. [seq] / [id] are set when they could be read (then the command is acked with the reason and the sequence moves on). */
    data class Rejected(val seq: Long?, val id: String?, val reason: String) : ParsedCommand()
}

/** One poll answer: each command parsed on its own (one bad command never costs the others), and the server's own clock. */
data class ParsedBatch(val commands: List<ParsedCommand>, val serverNowMs: Long?)

/**
 * Strict, pure parser/validator of the relayed commands. Unknown fields are ignored; wrong types, out-of-range values, over-long text and
 * any type that is not on the whitelist are rejected with a short reason. Nothing here touches Android or the network.
 */
object RemoteParser {
    const val MAX_TICKER = Ticker.MAX_LENGTH
    const val MAX_ID = 64
    const val MAX_PAYLOAD_CHARS = 4096
    const val MAX_COMMANDS_PER_ANSWER = 100

    /** Types the phone can apply. Anything not in this set or [UNSUPPORTED_TYPES] (stop, start, camera, bitrate, recording, destinations ...) is refused. */
    val SUPPORTED_TYPES = setOf("ads.manual", "ads.enabled", "ads.shrink", "ads.schedule", "ticker.set", "clock.set")
    /** Whitelisted by the server but without an equivalent on the phone: payload must be an object, then acked "not supported". */
    val UNSUPPORTED_TYPES = setOf("logo.set", "kit.apply", "layout.set", "lowerThird.set", "wallpaper.dim")

    private val ID_RE = Regex("[A-Za-z0-9_-]{1,$MAX_ID}")
    private val json = Json { ignoreUnknownKeys = true }

    fun parseBatch(body: String): ParsedBatch {
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
            ?: return ParsedBatch(emptyList(), null)
        val now = (root["now"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { parseInstantMs(it) }
        val list = (root["commands"] as? JsonArray).orEmpty().take(MAX_COMMANDS_PER_ANSWER)
        return ParsedBatch(list.map { parse(it) }, now)
    }

    fun parse(el: JsonElement): ParsedCommand {
        val o = el as? JsonObject ?: return ParsedCommand.Rejected(null, null, "command is not an object")
        val seq = intLong(o["seq"])?.takeIf { it > 0 }
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { ID_RE.matches(it) }
        fun no(reason: String) = ParsedCommand.Rejected(seq, id, reason)
        if (seq == null) return no("bad seq")
        if (id == null) return no("bad id")
        val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return no("bad type")
        val at = (o["at"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { parseInstantMs(it) } ?: return no("bad at")
        if (type !in SUPPORTED_TYPES && type !in UNSUPPORTED_TYPES) return no("command not allowed")
        val payload = o["payload"] as? JsonObject ?: return no("payload missing")
        if (payload.toString().length > MAX_PAYLOAD_CHARS) return no("payload too large")
        val action = try { action(type, payload) } catch (e: Bad) { return no(e.message ?: "invalid") }
        return ParsedCommand.Ok(RemoteCommand(seq, id, type, at, action))
    }

    private class Bad(msg: String) : Exception(msg)

    private fun action(type: String, p: JsonObject): RemoteAction = when (type) {
        "ads.manual" -> RemoteAction.AdsManual(placement(p), manualMode(p))
        "ads.enabled" -> RemoteAction.AdsEnabled(placement(p), bool(p, "on"))
        "ads.shrink" -> RemoteAction.AdsShrink(bool(p, "on"))
        "ads.schedule" -> RemoteAction.AdsSchedule(placement(p), schedule(p))
        "ticker.set" -> ticker(p)
        "clock.set" -> RemoteAction.ClockSet(clock(p))
        else -> RemoteAction.Unsupported()
    }

    private fun placement(p: JsonObject): AdPlacement = AdPlacement.fromWire(str(p, "placement")) ?: throw Bad("unknown placement")

    private fun manualMode(p: JsonObject): AdManual? {
        if (!p.containsKey("mode")) throw Bad("mode missing")
        val m = p["mode"]
        if (m is JsonNull) return null
        return when ((m as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "shown" -> AdManual.SHOWN
            "hidden" -> AdManual.HIDDEN
            else -> throw Bad("mode must be shown, hidden or null")
        }
    }

    private fun schedule(p: JsonObject): AdSchedule = when (str(p, "mode")) {
        "continuous" -> AdSchedule.Continuous
        "interval" -> {
            val show = intLong(p["showSec"]) ?: throw Bad("showSec must be a whole number")
            val every = intLong(p["everySec"]) ?: throw Bad("everySec must be a whole number")
            if (show !in AD_SHOW_SEC_MIN..AD_SHOW_SEC_MAX) throw Bad("showSec out of range")
            if (every !in AD_EVERY_SEC_MIN..AD_EVERY_SEC_MAX) throw Bad("everySec out of range")
            AdSchedule.Interval(show.toInt(), every.toInt())
        }
        else -> throw Bad("mode must be continuous or interval")
    }

    private fun ticker(p: JsonObject): RemoteAction.TickerSet {
        val on = bool(p, "on")
        if (!p.containsKey("text")) throw Bad("text missing")
        val t = p["text"]
        val text = when {
            t is JsonNull -> null
            t is JsonPrimitive && t.isString -> t.content
            else -> throw Bad("text must be a string or null")
        }
        if (text != null && text.length > MAX_TICKER) throw Bad("text longer than $MAX_TICKER")
        return RemoteAction.TickerSet(text, on)
    }

    private fun clock(p: JsonObject): ClockPatch {
        val known = listOf("enabled", "position", "format", "showDate", "showSeconds", "timezone", "size", "style", "alternate")
        if (known.none { p.containsKey(it) }) throw Bad("no clock fields")
        fun optBool(key: String): Boolean? = if (p.containsKey(key)) bool(p, key) else null
        fun optStr(key: String): String? = if (p.containsKey(key)) str(p, key) else null
        val zoneGiven = p.containsKey("timezone")
        var zone: String? = null
        if (zoneGiven && p["timezone"] !is JsonNull) {
            val s = (p["timezone"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Bad("timezone must be a string or null")
            if (s.length > Clock.TZ_MAX) throw Bad("timezone too long")
            zone = Clock.cleanZone(s)
            if (zone != null && !validZone(zone)) throw Bad("unknown timezone")
        }
        return ClockPatch(
            enabled = optBool("enabled"),
            position = optStr("position")?.let { w -> ClockPosition.entries.firstOrNull { it.wire == w } ?: throw Bad("unknown position") },
            hours = optStr("format")?.let { w -> ClockHours.entries.firstOrNull { it.wire == w } ?: throw Bad("unknown format") },
            showDate = optBool("showDate"), showSeconds = optBool("showSeconds"),
            zoneGiven = zoneGiven, zone = zone,
            size = optStr("size")?.let { w -> ClockSize.entries.firstOrNull { it.wire == w } ?: throw Bad("unknown size") },
            style = optStr("style")?.let { w -> ClockStyle.entries.firstOrNull { it.wire == w } ?: throw Bad("unknown style") },
            alternate = optBool("alternate"),
        )
    }

    private fun validZone(z: String) = try { java.time.ZoneId.of(z); true } catch (_: Exception) { false }

    private fun str(p: JsonObject, key: String): String =
        (p[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Bad("$key must be a string")

    private fun bool(p: JsonObject, key: String): Boolean =
        (p[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw Bad("$key must be true or false")

    /** A JSON number that is a whole number (1 and 1.0 are fine, "1" and 1.5 are not). */
    private fun intLong(el: JsonElement?): Long? {
        val p = el as? JsonPrimitive ?: return null
        if (p.isString) return null
        p.longOrNull?.let { return it }
        val d = p.content.toDoubleOrNull() ?: return null
        return if (d == Math.rint(d) && Math.abs(d) < 9.0e15) d.toLong() else null
    }

    fun parseInstantMs(s: String): Long? = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        try { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }
}
