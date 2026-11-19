package app.streambird.android.remote

import app.streambird.android.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Turns one poll answer into outcomes, strictly in `seq` order: remembers the last sequence (a retry or a repeated answer never re-applies),
 * dedupes by id, drops stale commands (`at` older than [MAX_AGE_MS] against the server's own `now`), refuses when nothing is live, limits the
 * rate, and hands the rest to the [applier]. Used from one coroutine only. Log lines carry the type and the outcome, never any text content.
 */
class RemoteDispatcher(
    private val applier: RemoteApplier,
    private val isLive: () -> Boolean,
    private val rate: RateLimiter,
    private val wallNowMs: () -> Long,
    private val log: (String) -> Unit = {},
) {
    /** The sequence the next poll asks after. */
    var lastSeq: Long = 0L
        private set
    private val seenIds = LinkedHashSet<String>()

    data class Result(val acks: List<RemoteAck>, val appliedAny: Boolean)

    fun process(batch: ParsedBatch): Result {
        val acks = ArrayList<RemoteAck>()
        var applied = false
        val serverNow = batch.serverNowMs ?: wallNowMs()
        // Strictly in seq order, whatever order the server listed them in. Commands whose seq cannot be read go last (they only get an error ack).
        val ordered = batch.commands.sortedBy { c ->
            when (c) { is ParsedCommand.Ok -> c.command.seq; is ParsedCommand.Rejected -> c.seq ?: Long.MAX_VALUE }
        }
        for (c in ordered) {
            when (c) {
                is ParsedCommand.Rejected -> {
                    val seq = c.seq
                    if (seq != null) { if (seq <= lastSeq) continue; remember(seq, c.id) }
                    log("remote: rejected seq=${seq ?: "?"} (${c.reason})")
                    c.id?.let { acks.add(ack(it, false, c.reason)) }
                }
                is ParsedCommand.Ok -> {
                    val cmd = c.command
                    if (cmd.seq <= lastSeq) { log("remote: ${cmd.type} seq=${cmd.seq} skipped (already seen)"); continue }
                    if (cmd.id in seenIds) { remember(cmd.seq, cmd.id); log("remote: ${cmd.type} seq=${cmd.seq} skipped (id already seen)"); continue }
                    remember(cmd.seq, cmd.id)
                    val (ok, error) = decide(cmd, serverNow)
                    if (ok) applied = true
                    log("remote: ${cmd.type} seq=${cmd.seq} -> " + if (ok) "ok" else "refused ($error)")
                    acks.add(ack(cmd.id, ok, error))
                }
            }
        }
        return Result(acks, applied)
    }

    private fun decide(cmd: RemoteCommand, serverNow: Long): Pair<Boolean, String?> {
        if (serverNow - cmd.atMs > MAX_AGE_MS) return false to "command too old"
        if (cmd.action !is RemoteAction.Unsupported && !isLive()) return false to "stream not live"
        if (!rate.tryAcquire()) return false to "rate limited"
        val r = applier.apply(cmd)
        return r.ok to r.error
    }

    private fun remember(seq: Long, id: String?) {
        if (seq > lastSeq) lastSeq = seq
        if (id != null) { seenIds.add(id); if (seenIds.size > MAX_IDS) seenIds.remove(seenIds.first()) }
    }

    private fun ack(id: String, ok: Boolean, error: String?) = RemoteAck(id, ok, error, wallNowMs())

    companion object {
        const val MAX_AGE_MS = 2 * 60_000L
        const val MAX_IDS = 500
    }
}

/**
 * The long-poll loop of one live stream. [start] when the stream is confirmed LIVE, [stop] when it ends / fails / the app stops (cancels the
 * open request). One idle connection, no timers while a request is open. 404 / 405 / 501 = the server does not have the feature (stop for this
 * stream, silently); 401 / 403 / 409 and other 4xx = stop; 429 / 5xx / network = exponential backoff 1 s .. 30 s with jitter, keep going.
 * A new stream gets a new client (or [start] again): nothing is remembered across streams.
 */
class RemoteControlClient(
    private val api: RemoteControlApi,
    private val dispatcher: RemoteDispatcher,
    private val scope: CoroutineScope,
    private val stateSource: () -> JsonObject?,
    private val nowMs: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: () -> Double = { Math.random() },
    private val log: (String) -> Unit = {},
) {
    @Volatile private var pollJob: Job? = null
    @Volatile private var stateJob: Job? = null
    @Volatile private var ackJob: Job? = null
    @Volatile private var stateOff = false
    private var rev = 0L
    private var acks = Channel<RemoteAck>(Channel.UNLIMITED)
    private var stateSignal = Channel<Unit>(Channel.CONFLATED)

    val running: Boolean get() = pollJob?.isActive == true

    @Synchronized fun start(streamId: String) {
        stop()
        stateOff = false; rev = 0L
        acks = Channel(Channel.UNLIMITED); stateSignal = Channel(Channel.CONFLATED)
        log("remote control: start")
        val a = acks; val s = stateSignal
        ackJob = scope.launch(CoroutineName("remote-ack")) { ackLoop(streamId, a) }
        stateJob = scope.launch(CoroutineName("remote-state")) { stateLoop(streamId, s) }
        pollJob = scope.launch(CoroutineName("remote-poll")) { pollLoop(streamId) }
        stateChanged()
    }

    @Synchronized fun stop() {
        val had = pollJob != null
        pollJob?.cancel(); stateJob?.cancel(); ackJob?.cancel()
        pollJob = null; stateJob = null; ackJob = null
        acks.close(); stateSignal.close()
        if (had) log("remote control: stop")
    }

    /** The phone's settings changed (a command was applied, or the host changed something): schedules a state upload (debounced, at most one per second). */
    fun stateChanged() { stateSignal.trySend(Unit) }

    private fun stopForGood(why: String) {
        log("remote control: $why")
        stateJob?.cancel(); ackJob?.cancel()
        // pollJob ends by returning from the loop
    }

    private suspend fun pollLoop(streamId: String) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val t0 = nowMs()
            val body = try {
                api.poll(streamId, dispatcher.lastSeq).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when {
                    e.status in UNSUPPORTED -> { stopForGood("not supported by this server (HTTP ${e.status})"); return }
                    e.status == 401 || e.status == 403 || e.status == 409 -> { stopForGood("stopped (HTTP ${e.status})"); return }
                    e.status in 400..499 && e.status != 408 && e.status != 429 -> { stopForGood("stopped (HTTP ${e.status})"); return }
                    else -> { failures++; backoff(failures, "HTTP ${e.status}"); continue }
                }
            } catch (e: Throwable) {
                failures++; backoff(failures, e.javaClass.simpleName); continue
            }
            val batch = RemoteParser.parseBatch(body)
            if (batch.commands.isEmpty()) {
                // 204 / nothing new: poll again with the same `after`. A server that answers instantly must not be hammered.
                val waited = nowMs() - t0
                if (waited < MIN_EMPTY_POLL_MS) sleep(MIN_EMPTY_POLL_MS - waited)
                continue
            }
            val result = try { dispatcher.process(batch) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                log("remote: error while applying: ${e.javaClass.simpleName}"); continue
            }
            result.acks.forEach { acks.trySend(it) }
            if (result.appliedAny) stateChanged()
        }
    }

    private suspend fun backoff(failures: Int, what: String) {
        val base = minOf(MAX_BACKOFF_MS, MIN_BACKOFF_MS shl (failures - 1).coerceIn(0, 10))
        val ms = minOf(MAX_BACKOFF_MS, (base * (0.75 + 0.5 * random())).toLong()).coerceAtLeast(MIN_BACKOFF_MS / 2)
        if (failures == 1 || failures % 5 == 0) log("remote control: poll failed ($what), retry in ${ms} ms")
        sleep(ms)
    }

    private suspend fun ackLoop(streamId: String, ch: Channel<RemoteAck>) {
        for (ack in ch) {
            var tries = 0
            while (tries < 2) {
                tries++
                try { api.ack(streamId, ack); break } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    if (e is ApiException && e.status in UNSUPPORTED) break
                    if (tries < 2) sleep(ACK_RETRY_MS) else log("remote control: ack failed (${(e as? ApiException)?.status ?: e.javaClass.simpleName})")
                }
            }
        }
    }

    private suspend fun stateLoop(streamId: String, signal: Channel<Unit>) {
        var lastSentAt = Long.MIN_VALUE / 2
        for (u in signal) {
            if (stateOff) continue
            sleep(STATE_DEBOUNCE_MS)                                   // changes within the debounce go out together (the signal is conflated)
            val since = nowMs() - lastSentAt
            if (since < STATE_MIN_GAP_MS) sleep(STATE_MIN_GAP_MS - since) // at most one upload per second
            val snapshot = try { stateSource() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null } ?: continue
            lastSentAt = nowMs()
            try {
                api.putState(streamId, ++rev, snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is ApiException && (e.status in UNSUPPORTED || e.status == 401 || e.status == 403 || e.status == 409)) { stateOff = true; log("remote control: state upload stopped (HTTP ${e.status})") }
            }
        }
    }

    companion object {
        val UNSUPPORTED = setOf(404, 405, 501)
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MIN_EMPTY_POLL_MS = 1_000L
        const val STATE_DEBOUNCE_MS = 300L
        const val STATE_MIN_GAP_MS = 1_000L
        const val ACK_RETRY_MS = 500L
    }
}
