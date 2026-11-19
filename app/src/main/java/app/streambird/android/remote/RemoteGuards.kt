package app.streambird.android.remote

import java.util.concurrent.ConcurrentHashMap

/** At most [max] commands per [windowMs] (sliding window); the rest are refused. [nowMs] is any monotonic millisecond clock (tests pass a fake). */
class RateLimiter(private val max: Int = 20, private val windowMs: Long = 10_000, private val nowMs: () -> Long) {
    private val times = ArrayDeque<Long>()

    @Synchronized fun tryAcquire(): Boolean {
        val now = nowMs()
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }
}

/**
 * The conflict rule: the HOST'S OWN CHANGES ALWAYS WIN. The Live screen records the time of every host-originated change per setting key; a
 * remote command for a key the host touched within the last [windowMs] is refused ("host changed it just now"). A later host change simply
 * overrides a remote one: nothing is ever locked.
 */
class HostChangeTracker(private val windowMs: Long = WINDOW_MS, private val nowMs: () -> Long) {
    private val last = ConcurrentHashMap<String, Long>()

    fun mark(key: String) { last[key] = nowMs() }

    fun recent(key: String): Boolean {
        val t = last[key] ?: return false
        return nowMs() - t in 0 until windowMs
    }

    fun clear() = last.clear()

    companion object { const val WINDOW_MS = 3_000L }
}
