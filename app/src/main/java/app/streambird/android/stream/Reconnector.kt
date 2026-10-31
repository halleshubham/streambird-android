package app.streambird.android.stream

import app.streambird.android.media.ConnPhase
import app.streambird.android.media.EncoderStats
import app.streambird.android.media.ReconnectMachine
import app.streambird.android.media.StallDetector
import app.streambird.android.media.Step
import app.streambird.android.media.Trigger
import app.streambird.android.media.feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Live screen shows about the connection. */
data class ReconnectUi(val phase: ConnPhase = ConnPhase.OK, val attempt: Int = 0, val reason: String = "", val backOnlineAtMs: Long = 0) {
    val phaseBusy get() = phase == ConnPhase.RECONNECTING || phase == ConnPhase.WAITING_NETWORK
}

interface ReconnectHost {
    /** Fresh PeerConnection + WHIP POST to the same stream URL, then hang up the old one. Throws on failure. */
    suspend fun republish()
    fun log(text: String)
    fun onUi(ui: ReconnectUi)
    fun nowMs(): Long
}

/**
 * Runs the [ReconnectMachine] against the real world: feeds it detections (network, peer-connection state, stalled feedback),
 * runs the attempts with backoff, replaces a pending attempt when the network changes again. The stream itself (camera, encoder,
 * server-side stream, foreground service) is never touched; /end is never called from here.
 */
class Reconnector(private val scope: CoroutineScope, private val host: ReconnectHost) {
    private val machine = ReconnectMachine()
    private val detector = StallDetector()
    private var job: Job? = null
    private var lastStatsMs = 0.0
    private var triggerStatsMs = 0.0

    val phase: ConnPhase @Synchronized get() = machine.phase

    @Synchronized fun onTrigger(t: Trigger) {
        host.log("detected: ${t.label}")
        val was = machine.phase
        apply(machine.trigger(t, host.nowMs()), t)
        if (was == ConnPhase.OK) host.onUi(ReconnectUi(machine.phase, machine.attempt, t.label))
        else publishUi(t.label)
    }

    /** Peer connection state of the CURRENT connection. */
    @Synchronized fun onPeerState(state: String) {
        when (state) {
            "DISCONNECTED" -> if (machine.phase == ConnPhase.OK) onTrigger(Trigger.IceDisconnected)
            "FAILED", "CLOSED" -> if (machine.phase == ConnPhase.OK) onTrigger(Trigger.IceFailed(state))
            "CONNECTED" -> if (machine.iceRecovered()) { job?.cancel(); host.log("ICE recovered by itself, no republish needed"); host.onUi(ReconnectUi()) }
        }
    }

    @Synchronized fun onStats(s: EncoderStats) {
        lastStatsMs = s.timestampMs
        if (detector.add(s.feedback()) && machine.phase == ConnPhase.OK) onTrigger(Trigger.Stall)
    }

    @Synchronized fun stop() { job?.cancel(); job = null; machine.stop(); host.onUi(ReconnectUi()) }

    private fun publishUi(reason: String) = host.onUi(ReconnectUi(machine.phase, machine.attempt, reason))

    private fun apply(step: Step, t: Trigger) {
        when (step) {
            Step.None -> { if (machine.phase == ConnPhase.WAITING_NETWORK) { job?.cancel(); host.log("waiting for a network") } }
            Step.GiveUp -> { job?.cancel(); host.log("giving up: the server ends the stream after 5 minutes without a picture") }
            is Step.Attempt -> schedule(step)
        }
    }

    @Synchronized private fun schedule(step: Step.Attempt) {
        job?.cancel()
        triggerStatsMs = lastStatsMs
        job = scope.launch {
            delay(step.afterMs)
            val skip = synchronized(this@Reconnector) { step.onlyIfStalled && detector.advancedSince(triggerStatsMs) }
            if (skip) {
                synchronized(this@Reconnector) { machine.cancelHarmless() }
                host.log("feedback still flowing after the change, no republish needed")
                host.onUi(ReconnectUi())
                return@launch
            }
            val n = synchronized(this@Reconnector) { machine.attemptStarted() }
            host.log("republish attempt $n")
            host.onUi(ReconnectUi(ConnPhase.RECONNECTING, n, "reconnecting"))
            try {
                host.republish()
                val secs = synchronized(this@Reconnector) { detector.reset(); machine.attemptSucceeded(host.nowMs()) }
                host.log("republished ok after ${String.format(java.util.Locale.US, "%.1f", secs)} s (attempt $n)")
                host.onUi(ReconnectUi(ConnPhase.OK, 0, "", backOnlineAtMs = host.nowMs()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                host.log("republish attempt $n failed: ${e.message ?: e.javaClass.simpleName}")
                val next = synchronized(this@Reconnector) { machine.attemptFailed(host.nowMs()) }
                if (next is Step.GiveUp) {
                    host.onUi(ReconnectUi(ConnPhase.GAVE_UP, n, "gave up"))
                    host.log("giving up: no picture for 5 minutes, the server ends the stream")
                } else {
                    host.onUi(ReconnectUi(ConnPhase.RECONNECTING, n, "retrying"))
                    if (next is Step.Attempt) { host.log("next attempt in ${next.afterMs / 1000} s"); schedule(next) }
                }
            }
        }
    }
}
