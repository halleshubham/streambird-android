package app.streambird.android.preset

import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.Preset
import app.streambird.android.data.PresetConfig
import app.streambird.android.data.createPreset
import app.streambird.android.data.deletePreset
import app.streambird.android.data.listPresets
import app.streambird.android.data.updatePreset
import app.streambird.android.data.usePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** The calls the controller needs; [ApiPresets] is the real one, tests use a fake. */
interface PresetsPort {
    suspend fun list(): List<Preset>
    suspend fun create(name: String, config: PresetConfig): Preset
    suspend fun update(id: String, name: String?, config: PresetConfig?): Preset
    suspend fun delete(id: String)
    suspend fun use(id: String): Preset
}

class ApiPresets(private val api: ApiClient) : PresetsPort {
    override suspend fun list() = api.listPresets()
    override suspend fun create(name: String, config: PresetConfig) = api.createPreset(name, config)
    override suspend fun update(id: String, name: String?, config: PresetConfig?) = api.updatePreset(id, name, config)
    override suspend fun delete(id: String) = api.deletePreset(id)
    override suspend fun use(id: String) = api.usePreset(id)
}

/**
 * [available] false = the server has NO presets feature (the endpoint answers 404/405/501, an older server): the UI hides the presets section and offers
 * today's flow with one implicit default card. Any OTHER trouble (offline, 5xx, a captive-portal page, a timeout at start-up) never hides it:
 * [loadError] says the list could not be loaded, the "Save as preset" block and "+ New preset" stay, and the list is pulled again when Home opens.
 * [items] is already in home order (most recently used first). [notice] is a short confirmation ("Preset saved"), [error] the reason a write failed.
 */
data class PresetsState(
    val loaded: Boolean = false,
    val available: Boolean = false,
    val items: List<Preset> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val loadError: String? = null,
)

/** A status that means "this server has no presets endpoint" (an older server), as opposed to trouble with a server that has it. */
fun isFeatureMissing(status: Int) = status == 404 || status == 405 || status == 501

/** Process-wide presets state. Never throws for server/network problems: they become [PresetsState.available] = false (feature missing only) or messages. */
class PresetController(
    private val port: PresetsPort,
    /** Diagnostics sink: every failed preset call is logged with its HTTP status and a body snippet (default: the app's event log + logcat). */
    private val log: (String) -> Unit = ::defaultPresetLog,
) {
    private val _state = MutableStateFlow(PresetsState())
    val state: StateFlow<PresetsState> = _state.asStateFlow()

    /** Loads the list. Only a missing endpoint hides the section; any other failure keeps the cards already shown and records [PresetsState.loadError]. */
    suspend fun refresh() {
        try {
            val items = orderForHome(port.list())
            _state.update { it.copy(loaded = true, available = true, items = items, loadError = null) }
            log("presets: list ok, ${items.size} preset(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("presets: list failed ${describe(e)}")
            if (e is ApiException && isFeatureMissing(e.status)) {
                _state.update { PresetsState(loaded = true, available = false) }
            } else {
                val why = if (e is IOException && e !is ApiException) "Cannot reach the server: ${e.message}" else (e.message ?: e.javaClass.simpleName)
                _state.update { it.copy(loaded = true, available = true, loadError = "Could not load your presets. $why") }
            }
        }
    }

    /**
     * Creates ([id] null) or updates a preset, then re-reads the list so the screen shows what the server really has. Returns the saved preset,
     * or null with [PresetsState.error] set. A success sets [PresetsState.notice] ("Preset saved" / "Preset updated").
     */
    suspend fun save(id: String?, name: String, config: PresetConfig): Preset? {
        val clean = name.trim()
        if (clean.isEmpty()) { _state.update { it.copy(error = "Give the preset a name.", notice = null) }; return null }
        val saved = guarded(if (id == null) "create" else "update") {
            val saved = try {
                if (id == null) port.create(clean, config) else port.update(id, clean, config)
            } catch (e: ApiException) {
                if (id != null && e.status == 404) _state.update { s -> s.copy(items = s.items.filter { it.id != id }) } // gone elsewhere: stop offering it
                throw e
            }
            _state.update { s ->
                s.copy(items = orderForHome(s.items.filter { it.id != saved.id } + saved), notice = if (id == null) "Preset saved" else "Preset updated", loadError = null)
            }
            saved
        }
        if (saved != null) reconcile()
        return saved
    }

    suspend fun delete(id: String): Boolean = guarded("delete") {
        // 404 = already gone (deleted on another device): that is what the user wanted.
        try { port.delete(id) } catch (e: ApiException) { if (e.status != 404) throw e }
        _state.update { s -> s.copy(items = s.items.filter { it.id != id }, notice = "Preset deleted") }
        true
    } ?: false

    /** Records that the preset was just used. Silent: a failure here never blocks going live. */
    suspend fun markUsed(id: String) {
        try {
            val used = port.use(id)
            _state.update { s ->
                s.copy(items = orderForHome(s.items.map { if (it.id == id) used.copy(config = if (used.config == PresetConfig()) it.config else used.config) else it }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("presets: use failed ${describe(e)}")
        }
    }

    /** Forget everything (the account was deleted). */
    fun reset() { _state.value = PresetsState() }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** After a write: replace the list by the server's own, silently (a failure here keeps what the write just produced). */
    private suspend fun reconcile() {
        try {
            val items = orderForHome(port.list())
            _state.update { it.copy(items = items, loaded = true, available = true, loadError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("presets: re-read after save failed ${describe(e)}")
        }
    }

    private suspend fun <T : Any> guarded(op: String, block: suspend () -> T): T? {
        _state.update { it.copy(busy = true, error = null, notice = null) }
        return try {
            block().also { log("presets: $op ok") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            log("presets: $op failed ${describe(e)}")
            when {
                op == "create" && isFeatureMissing(e.status) && _state.value.items.isEmpty() ->
                    _state.update { PresetsState(loaded = true, available = false) }
                else -> _state.update { it.copy(error = writeError(op, e)) }
            }
            null
        } catch (e: IOException) {
            log("presets: $op failed ${describe(e)}")
            _state.update { it.copy(error = "Cannot reach the server: ${e.message}") }
            null
        } catch (e: Exception) {
            log("presets: $op failed ${describe(e)}")
            _state.update { it.copy(error = "Something went wrong (${e.javaClass.simpleName}). Try again.") }
            null
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun writeError(op: String, e: ApiException): String = when {
        e.status == 404 && op == "update" -> "That preset no longer exists (it may have been deleted on another device). Save it again as a new preset."
        e.status == 404 && op == "delete" -> "That preset no longer exists."
        e.status == 401 -> "Your session expired. Sign in again."
        else -> "${if (op == "delete") "Could not delete the preset" else "Could not save the preset"}: ${e.message}"
    }

    private fun describe(e: Exception) =
        if (e is ApiException) "HTTP ${e.status} ${e.message} body=${e.body.replace('\n', ' ').take(200)}" else "${e.javaClass.simpleName}: ${e.message}"
}

/** Event log (Share crash report / diagnostics) + logcat. Never throws. */
fun defaultPresetLog(line: String) {
    runCatching { app.streambird.android.diag.Diag.log(line) }
    runCatching { android.util.Log.i("StreamBirdPresets", line) }
}
