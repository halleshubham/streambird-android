package app.streambird.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The stop/republish rules of the engine, separated from WebRTC so they can be tested on the JVM:
 *  - [stop] runs its teardown at most once; concurrent or later callers wait for the first and return (no double dispose);
 *  - the teardown is NonCancellable: a cancelled caller cannot leave the camera / encoder half released;
 *  - [exclusive] (republish) and the teardown share ONE lock, and [exclusive] refuses to start once stop was called, so a
 *    republish can never run against, or leak a link past, a stopping engine.
 */
class EngineLifecycle(private val lockWaitMs: Long = 8_000) {
    private val stopped = AtomicBoolean(false)
    private val done = CompletableDeferred<Unit>()
    private val lock = Mutex()

    val isStopped get() = stopped.get()

    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock {
        if (stopped.get()) throw CancellationException("engine stopped")
        block()
    }

    /** [teardown] gets true when it holds the republish lock (false after a lock timeout: proceed anyway, a hung republish must not block the end). */
    suspend fun stop(teardown: suspend (lockHeld: Boolean) -> Unit) {
        if (!stopped.compareAndSet(false, true)) { done.await(); return }
        try {
            withContext(NonCancellable) {
                val held = withTimeoutOrNull(lockWaitMs) { lock.lock(); true } == true
                try { teardown(held) } finally { if (held) lock.unlock() }
            }
        } finally {
            done.complete(Unit)
        }
    }
}
