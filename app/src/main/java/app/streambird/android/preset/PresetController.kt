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
 * [available] false = the server has no presets feature (404) or cannot be reached: the UI hides the presets section and offers
 * today's flow with one implicit default card. [items] is already in home order (most recently used first).
 */
data class PresetsState(
    val loaded: Boolean = false,
    val available: Boolean = false,
    val items: List<Preset> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/** Process-wide presets state. Never throws for server/network problems: they become [PresetsState.available] = false or [PresetsState.error]. */
class PresetController(private val port: PresetsPort) {
    private val _state = MutableStateFlow(PresetsState())
    val state: StateFlow<PresetsState> = _state.asStateFlow()

    /** Loads the list. 404 (older server) or any failure with nothing loaded before -> hidden; a failure after a good load keeps what was shown. */
    suspend fun refresh() {
        try {
            val items = orderForHome(port.list())
            _state.update { PresetsState(loaded = true, available = true, items = items) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val keep = e !is ApiException || e.status != 404
            _state.update { s ->
                if (keep && s.available) s.copy(loaded = true) // transient trouble: keep the cards on screen
                else PresetsState(loaded = true, available = false)
            }
        }
    }

    /** Creates ([id] null) or updates a preset. Returns it, or null with [PresetsState.error] set. */
    suspend fun save(id: String?, name: String, config: PresetConfig): Preset? {
        val clean = name.trim()
        if (clean.isEmpty()) { _state.update { it.copy(error = "Give the preset a name.") }; return null }
        return guarded {
            val saved = if (id == null) port.create(clean, config) else port.update(id, clean, config)
            _state.update { s -> s.copy(items = orderForHome(s.items.filter { it.id != saved.id } + saved)) }
            saved
        }
    }

    suspend fun delete(id: String): Boolean = guarded {
        port.delete(id)
        _state.update { s -> s.copy(items = s.items.filter { it.id != id }) }
        true
    } ?: false

    /** Records that the preset was just used. Silent: a failure here never blocks going live. */
    suspend fun markUsed(id: String) {
        try {
            val used = port.use(id)
            _state.update { s -> s.copy(items = orderForHome(s.items.map { if (it.id == id) used.copy(config = if (used.config == PresetConfig()) it.config else used.config) else it })) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun <T> guarded(block: suspend () -> T): T? {
        _state.update { it.copy(busy = true, error = null) }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 404 && _state.value.items.isEmpty()) _state.update { PresetsState(loaded = true, available = false) }
            else _state.update { it.copy(error = e.message) }
            null
        } catch (e: IOException) {
            _state.update { it.copy(error = "Cannot reach the server: ${e.message}") }
            null
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
