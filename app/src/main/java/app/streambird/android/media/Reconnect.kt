package app.streambird.android.media

/**
 * Pure decision logic for surviving a network change or a dead transport (no Android, no WebRTC: JVM-tested).
 *
 * Why it exists: in a field log (cellular -> none -> wifi) the encoder kept producing frames while rtt, nack and pli froze,
 * i.e. no RTCP feedback arrived any more, the ICE pair on the old interface was dead, and nothing recovered it.
 * The reliable recovery is a RE-PUBLISH on a fresh PeerConnection (new WHIP POST to the same stream URL); the server shows a
 * "technical difficulties" slate to the platforms for up to 5 minutes (GlitchRecoveryService) and swaps the real picture back in.
 */

enum class ConnPhase { OK, WAITING_NETWORK, RECONNECTING, GAVE_UP }

sealed class Trigger(val label: String) {
    /** The default network changed to another one (wifi <-> cellular or a different wifi). */
    data class NetworkChanged(val from: String, val to: String) : Trigger("network $from -> $to")
    /** No default network at all (airplane mode, tunnel). */
    object NetworkLost : Trigger("network lost")
    object IceDisconnected : Trigger("ICE disconnected")
    data class IceFailed(val state: String) : Trigger("peer connection $state")
    object Stall : Trigger("no feedback from the server (stall)")
    object Manual : Trigger("simulated drop")
}

sealed interface Step {
    object None : Step
    /** Run attempt [number] after [afterMs]. [onlyIfStalled]: skip it if feedback is flowing again by then (a network change that libwebrtc handled itself). */
    data class Attempt(val afterMs: Long, val number: Int, val onlyIfStalled: Boolean) : Step
    object GiveUp : Step
}

/** The server keeps the stream for 5 minutes without a publisher (GLITCH_WINDOW_MS); trying much longer is pointless. */
const val GIVE_UP_AFTER_MS = 5 * 60_000L

/** Wait after attempt [failedAttempt] (1-based) failed: 1, 2, 4, 8, 16, then 30 s. */
fun backoffMs(failedAttempt: Int): Long = (1000L shl (failedAttempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

class ReconnectMachine(private val giveUpAfterMs: Long = GIVE_UP_AFTER_MS) {
    var phase: ConnPhase = ConnPhase.OK; private set
    /** Attempts started in the current episode. */
    var attempt = 0; private set
    var startedAtMs = 0L; private set
    private var lastTrigger: Trigger? = null

    /** A detection fired. Returns what to do about it. */
    fun trigger(t: Trigger, now: Long): Step = when (phase) {
        ConnPhase.OK, ConnPhase.GAVE_UP -> begin(t, now)
        ConnPhase.WAITING_NETWORK -> when (t) {
            is Trigger.NetworkChanged -> { phase = ConnPhase.RECONNECTING; attempt = 0; Step.Attempt(1000, 1, false) }
            else -> Step.None
        }
        ConnPhase.RECONNECTING -> when (t) {
            // The network moved again: drop the pending/running attempt and start over with the fast schedule.
            is Trigger.NetworkChanged -> { attempt = 0; Step.Attempt(1000, 1, false) }
            Trigger.NetworkLost -> { phase = ConnPhase.WAITING_NETWORK; Step.None }
            else -> Step.None
        }
    }

    private fun begin(t: Trigger, now: Long): Step {
        startedAtMs = now; attempt = 0; lastTrigger = t
        return when (t) {
            Trigger.NetworkLost -> { phase = ConnPhase.WAITING_NETWORK; Step.None }
            is Trigger.NetworkChanged -> { phase = ConnPhase.RECONNECTING; Step.Attempt(3000, 1, true) }
            Trigger.IceDisconnected -> { phase = ConnPhase.RECONNECTING; Step.Attempt(4000, 1, true) }
            else -> { phase = ConnPhase.RECONNECTING; Step.Attempt(0, 1, false) }
        }.also { if (it is Step.Attempt) attempt = 0 }
    }

    /** The scheduled attempt actually starts now (counts it). */
    fun attemptStarted(): Int = ++attempt

    /** The attempt failed (5xx, timeout, ICE did not connect). */
    fun attemptFailed(now: Long): Step {
        if (phase != ConnPhase.RECONNECTING) return Step.None
        if (now - startedAtMs >= giveUpAfterMs) { phase = ConnPhase.GAVE_UP; return Step.GiveUp }
        return Step.Attempt(backoffMs(attempt.coerceAtLeast(1)), attempt + 1, false)
    }

    /** Returns the seconds the episode took. */
    fun attemptSucceeded(now: Long): Double { val s = (now - startedAtMs) / 1000.0; phase = ConnPhase.OK; attempt = 0; return s }

    /** The detection turned out harmless (ICE came back by itself / feedback is flowing): back to OK without republishing. */
    fun cancelHarmless() { if (phase == ConnPhase.RECONNECTING) { phase = ConnPhase.OK; attempt = 0 } }

    /** Peer connection reported CONNECTED while we were still only waiting out a grace period. */
    fun iceRecovered(): Boolean {
        if (phase == ConnPhase.RECONNECTING && attempt == 0 && lastTrigger == Trigger.IceDisconnected) { cancelHarmless(); return true }
        return false
    }

    fun stop() { phase = ConnPhase.OK; attempt = 0 }
}

// ---- stall detection ----

/** The feedback-related counters of one stats sample (a frozen set while bytesSent advances = the transport is dead). */
data class FeedbackSample(
    val tMs: Double,
    val bytesSent: Long,
    val rttMs: Double?,
    val nackCount: Long,
    val pliCount: Long,
    /** candidate-pair bytesReceived / responsesReceived and remote-inbound roundTripTimeMeasurements: advance with every RTCP report when available. */
    val pairBytesReceived: Long? = null,
    val pairResponsesReceived: Long? = null,
    val rttMeasurements: Long? = null,
) {
    fun signature() = listOf(rttMs, nackCount, pliCount, pairBytesReceived, pairResponsesReceived, rttMeasurements)
}

fun EncoderStats.feedback() = FeedbackSample(timestampMs, bytesSent, rttMs, nackCount, pliCount, pairBytesReceived, pairResponsesReceived, rttMeasurements)

/**
 * Flags a stalled transport: our send counter keeps advancing but nothing we get back (RTT, NACK/PLI counters, candidate-pair
 * receive counters) has changed for [stallMs]. If we are not sending either, that is an idle encoder, not a stall.
 */
class StallDetector(private val stallMs: Long = 7_000) {
    private var lastSig: List<Any?>? = null
    private var frozenSinceMs = 0.0
    private var lastBytes = -1L
    private var sentSinceFrozen = 0L
    /** Time of the last sample whose feedback signature differed from the one before. */
    var lastChangeMs = -1.0; private set

    /** Returns true when this sample shows a stall. */
    fun add(s: FeedbackSample): Boolean {
        val sig = s.signature()
        if (lastSig == null || sig != lastSig) {
            if (lastSig != null) lastChangeMs = s.tMs
            lastSig = sig; frozenSinceMs = s.tMs; sentSinceFrozen = 0; lastBytes = s.bytesSent
            return false
        }
        if (lastBytes >= 0 && s.bytesSent > lastBytes) sentSinceFrozen += s.bytesSent - lastBytes
        lastBytes = s.bytesSent
        return s.tMs - frozenSinceMs >= stallMs && sentSinceFrozen > MIN_BYTES
    }

    fun reset() { lastSig = null; lastBytes = -1; sentSinceFrozen = 0; lastChangeMs = -1.0 }

    /** Feedback changed at or after [sinceMs] (stats clock): the connection is alive. */
    fun advancedSince(sinceMs: Double) = lastChangeMs > sinceMs

    private companion object { const val MIN_BYTES = 2_000L }
}

// ---- network changes ----

data class NetState(val id: String?, val transport: String)

/** What the default-network callbacks mean for a running stream; null = nothing to do. */
fun networkTrigger(prev: NetState, cur: NetState): Trigger? = when {
    prev == cur -> null
    cur.id == null || cur.transport == "none" -> if (prev.id != null || prev.transport != "none") Trigger.NetworkLost else null
    prev.id == null || prev.transport == "none" -> Trigger.NetworkChanged(prev.transport, cur.transport)
    prev.id != cur.id || prev.transport != cur.transport -> Trigger.NetworkChanged(prev.transport, cur.transport)
    else -> null
}

/** Setup-screen hint when the phone is on mobile data (null = no hint). */
fun cellularHint(network: String, tier: StreamTier): String? =
    if (network != "cellular") null
    else "Mobile data detected: a steady ${tier.maxBitrateBps / 1_000_000} Mbps upload is needed for ${tier.label}. " +
        (if (tier == StreamTier.P540) "Use Wi-Fi if the picture gets blocky." else "Try 540p, or use Wi-Fi.")
