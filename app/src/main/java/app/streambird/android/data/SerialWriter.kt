package app.streambird.android.data

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Runs writes strictly in submission order on one thread (so an older save can never overtake a newer clear), and lets a caller
 * wait until everything submitted so far has been applied.
 */
class SerialWriter(name: String = "sb-writes") {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    private val dispatcher = exec.asCoroutineDispatcher()

    fun submit(block: () -> Unit) { exec.execute { runCatching(block) } }

    /** Completes after every earlier [submit]. */
    suspend fun flush() { withContext(dispatcher) { } }
}
