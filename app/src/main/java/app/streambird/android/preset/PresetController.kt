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
    /** A preset the host just deleted that is still on the server: hidden from [items] until the Undo window (see [UNDO_WINDOW_MS]) ends. */
    val pendingDelete: Preset? = null,
)

/** How long "Deleted ... Undo" stays available. The server delete happens only after it, so Undo is an exact restore (same id, same last-used time). */
const val UNDO_WINDOW_MS = 8_000L

const val NO_VISIBILITY_NOTE = ". This server does not store the visibility in presets yet; presets go live as set on this phone (public unless you choose otherwise)."

/** A 400 that names the `visibility` key as unknown (a server from before the preset visibility release). */
fun isVisibilityUnsupported(e: ApiException): Boolean {
    val m = (e.message + " " + e.body).lowercase()
    return e.status == 400 && "visibility" in m && "should not exist" in m
}

/** A status that means "this server has no presets endpoint" (an older server), as opposed to trouble with a server that has it. */
fun isFeatureMissing(status: Int) = status == 404 || status == 405 || status == 501

/** Process-wide presets state. Never throws for server/network problems: they become [PresetsState.available] = false (feature missing only) or messages. */
class PresetController(
    private val port: PresetsPort,
    /** Milliseconds clock for the Undo window (tests pass a fake one). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Diagnostics sink: every failed preset call is logged with its HTTP status and a body snippet (default: the app's event log + logcat). */
    private val log: (String) -> Unit = ::defaultPresetLog,
) {
    private val _state = MutableStateFlow(PresetsState())
    val state: StateFlow<PresetsState> = _state.asStateFlow()

    /** Ids deleted by the host that the server may still list (Undo window, or the delete call is on its way): never shown, even after a re-read. */
    private val hidden = mutableSetOf<String>()
    private var pendingSince = 0L

    private fun visible(list: List<Preset>) = orderForHome(list).filter { it.id !in hidden }

    /** Loads the list. Only a missing endpoint hides the section; any other failure keeps the cards already shown and records [PresetsState.loadError]. */
    suspend fun refresh() {
        try {
            val items = visible(port.list())
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
        var visibilityDropped = false
        if (clean.isEmpty()) { _state.update { it.copy(error = "Give the preset a name.", notice = null) }; return null }
        if (id == null) commitPendingDelete() // a delete still in its Undo window must free its slot before another preset is created
        val saved = guarded(if (id == null) "create" else "update") {
            suspend fun write(c: PresetConfig) = if (id == null) port.create(clean, c) else port.update(id, clean, c)
            val saved = try {
                try {
                    write(config)
                } catch (e: ApiException) {
                    // A server from before preset visibility rejects the unknown key: save without it and say so.
                    if (config.visibility != null && isVisibilityUnsupported(e)) { log("presets: server has no preset visibility, saved without it"); visibilityDropped = true; write(config.copy(visibility = null)) } else throw e
                }
            } catch (e: ApiException) {
                if (id != null && e.status == 404) _state.update { s -> s.copy(items = s.items.filter { it.id != id }) } // gone elsewhere: stop offering it
                throw e
            }
            _state.update { s ->
                s.copy(items = orderForHome(s.items.filter { it.id != saved.id } + saved), notice = (if (id == null) "Preset saved" else "Preset updated") + (if (visibilityDropped) NO_VISIBILITY_NOTE else ""), loadError = null)
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

    /**
     * Delete with Undo, step 1: hides the preset and starts the Undo window; the server is NOT called yet (so Undo is an exact restore: same id, same
     * last-used time, no re-create that could hit the 20 limit or lose the id). A delete already waiting is committed first. Returns false when the
     * preset is not in the list. If the app dies inside the window the preset simply stays (nothing was lost).
     */
    suspend fun beginDelete(p: Preset): Boolean {
        if (_state.value.items.none { it.id == p.id }) return false
        commitPendingDelete()
        hidden += p.id
        pendingSince = clock()
        _state.update { s -> s.copy(items = s.items.filter { it.id != p.id }, pendingDelete = p, error = null, notice = null) }
        return true
    }

    /** Step 2 (Undo tapped): the preset comes back where its last-used time puts it. Returns it, or null when nothing is waiting. */
    fun undoDelete(): Preset? {
        val p = _state.value.pendingDelete ?: return null
        hidden -= p.id
        _state.update { s -> s.copy(items = orderForHome(s.items.filter { it.id != p.id } + p), pendingDelete = null) }
        return p
    }

    /** Step 3 (timer): deletes on the server once the Undo window of [id] has ended; false when it was undone, replaced, or not due yet. */
    suspend fun commitDeleteIfDue(id: String): Boolean {
        val p = _state.value.pendingDelete ?: return false
        if (p.id != id || clock() - pendingSince < UNDO_WINDOW_MS) return false
        return commitPendingDelete()
    }

    /** Ends the Undo window now: the waiting delete goes to the server. A failure puts the preset back and says so. */
    suspend fun commitPendingDelete(): Boolean {
        val p = _state.value.pendingDelete ?: return false
        _state.update { it.copy(pendingDelete = null) }
        val ok = guarded("delete") {
            // 404 = already gone (deleted on another device): that is what the user wanted.
            try { port.delete(p.id) } catch (e: ApiException) { if (e.status != 404) throw e }
            _state.update { s -> s.copy(notice = "Preset deleted") }
            true
        } ?: false
        hidden -= p.id
        if (!ok) _state.update { s -> s.copy(items = orderForHome(s.items.filter { it.id != p.id } + p), error = (s.error ?: "Could not delete the preset.") + " It was put back.") }
        return ok
    }

    /** "Duplicate": a new preset with the same settings named "<name> copy" (unique, at most 80 characters). Null with [PresetsState.error] on failure. */
    suspend fun duplicate(p: Preset): Preset? {
        commitPendingDelete()
        if (presetLimitReached(_state.value.items.size)) { _state.update { it.copy(error = LIMIT_REACHED_TEXT, notice = null) }; return null }
        val name = duplicatePresetName(p.name, _state.value.items.map { it.name })
        val made = guarded("duplicate") {
            val m = port.create(name, p.config)
            _state.update { s -> s.copy(items = orderForHome(s.items.filter { it.id != m.id } + m), notice = "Created \"${m.name}\"", loadError = null) }
            m
        }
        if (made != null) reconcile()
        return made
    }

    /** "Rename": PATCH with the name only (the config is untouched). Same name = nothing to do. */
    suspend fun rename(id: String, name: String): Preset? {
        val clean = name.trim().take(Preset.MAX_NAME)
        if (clean.isEmpty()) { _state.update { it.copy(error = "Give the preset a name.", notice = null) }; return null }
        val old = _state.value.items.firstOrNull { it.id == id }
        if (old != null && old.name == clean) return old
        return guarded("rename") {
            val r = try {
                port.update(id, clean, null)
            } catch (e: ApiException) {
                if (e.status == 404) _state.update { s -> s.copy(items = s.items.filter { it.id != id }) }
                throw e
            }
            val merged = r.copy(config = if (r.config == PresetConfig()) old?.config ?: r.config else r.config)
            _state.update { s -> s.copy(items = orderForHome(s.items.map { if (it.id == id) merged else it }), notice = "Renamed to \"${merged.name}\"") }
            merged
        }
    }

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
    fun reset() { hidden.clear(); _state.value = PresetsState() }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** After a write: replace the list by the server's own, silently (a failure here keeps what the write just produced). */
    private suspend fun reconcile() {
        try {
            val items = visible(port.list())
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

    private fun writeError(op: String, e: ApiException): String = presetWriteError(op, e.status, e.message)

    private fun describe(e: Exception) =
        if (e is ApiException) "HTTP ${e.status} ${e.message} body=${e.body.replace('\n', ' ').take(200)}" else "${e.javaClass.simpleName}: ${e.message}"
}

/** Event log (Share crash report / diagnostics) + logcat. Never throws. */
fun defaultPresetLog(line: String) {
    runCatching { app.streambird.android.diag.Diag.log(line) }
    runCatching { android.util.Log.i("StreamBirdPresets", line) }
}
