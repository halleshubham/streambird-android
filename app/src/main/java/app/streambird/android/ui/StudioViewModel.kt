package app.streambird.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.AuthRepository
import app.streambird.android.data.EffectiveLimits
import app.streambird.android.data.PlatformConnection
import app.streambird.android.data.recentStreams
import app.streambird.android.media.PicturePriority
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.media.canSelectMore
import app.streambird.android.preset.PresetController
import app.streambird.android.preset.SetupChoices
import app.streambird.android.preset.SetupLimits
import app.streambird.android.preset.applyPresetConfig
import app.streambird.android.preset.choicesToConfig
import app.streambird.android.preset.implicitDefaultPreset
import app.streambird.android.stream.GoLiveRequest
import app.streambird.android.stream.LogoChoice
import app.streambird.android.stream.StreamController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class StudioUi(
    val loading: Boolean = true,
    val error: String? = null,
    val connections: List<PlatformConnection> = emptyList(),
    val limits: EffectiveLimits? = null,
    val selected: Set<String> = emptySet(),
    val limitNote: String? = null,
    val title: String = "",
    val visibility: String = "public",
    val tier: StreamTier = StreamTier.P720,
    val orientation: StreamOrientation = StreamOrientation.PORTRAIT,
    val priority: PicturePriority = PicturePriority.MAINTAIN_RESOLUTION,
    val frontCamera: Boolean = false,
    val h264HighProfile: Boolean = true,
    val forceKeyFrames: Boolean = true,
    val logoChoice: LogoChoice = LogoChoice.TEST,
    /** Photo-picker Uri of the optional stream thumbnail. */
    val thumbnailUri: String? = null,
    val tickerText: String = "",
    val tickerSize: app.streambird.android.media.TickerSize = app.streambird.android.media.TickerSize.S,
    /** Streams the server still lists as live that this phone is not publishing (e.g. left by a crash). */
    /** Per-stream ad changes made on the Setup screen (kit ads apply automatically with the kit). */
    val adOverrides: Map<app.streambird.android.ads.AdPlacement, app.streambird.android.ads.AdOverride> = emptyMap(),
    /** Per-stream "shrink picture while ads show": null = as the kit says. */
    val adsShrink: Boolean? = null,
    val staleStreams: List<app.streambird.android.data.StreamListItem> = emptyList(),
    val staleNote: String? = null,
    /** The saved preset the camera-first screen was opened with (null = the implicit default card or a new, unsaved preset). */
    val activePreset: app.streambird.android.data.Preset? = null,
    /** Things the preset could not apply (a channel was disconnected, quality above the plan...). */
    val presetNote: String? = null,
) {
    val brandAllowed get() = (limits?.assetStorageMb ?: 0) > 0
    val allowedTiers get() = StreamTier.allowed(limits?.maxResolution ?: "sd")
}

/** Channels + setup state shared by the Channels and Setup screens (activity-scoped). */
@HiltViewModel
class StudioViewModel @Inject constructor(
    private val api: ApiClient,
    private val auth: AuthRepository,
    private val settings: app.streambird.android.data.Settings,
    val controller: StreamController,
    private val brand: app.streambird.android.brand.BrandRepository,
    val presets: PresetController,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        StudioUi(
            tickerText = settings.tickerText,
            tickerSize = runCatching { app.streambird.android.media.TickerSize.valueOf(settings.tickerSize) }.getOrDefault(app.streambird.android.media.TickerSize.S),
        ),
    )
    val ui: StateFlow<StudioUi> = _ui.asStateFlow()

    init { refresh(); checkStaleStreams() }

    fun refresh() {
        checkStaleStreams()
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                coroutineScope {
                    val c = async { api.platformConnections() }
                    val l = async { api.myLimits() }
                    val conns = c.await().filter { it.isActive }
                    val limits = l.await()
                    _ui.update { s ->
                        val tier = if (s.tier in StreamTier.allowed(limits.maxResolution)) s.tier else StreamTier.best(limits.maxResolution)
                        s.copy(
                            loading = false, connections = conns, limits = limits, tier = tier,
                            selected = s.selected.filter { id -> conns.any { it.id == id } }.take(limits.maxDestinations).toSet(),
                            // Free has no brand library: default to the test overlay so the spike can still be exercised.
                            logoChoice = if (limits.assetStorageMb > 0 && s.logoChoice == LogoChoice.TEST) LogoChoice.BRAND else s.logoChoice,
                        )
                    }
                }
                // Presets and brand kits only decorate the home cards: they never fail the screen (an older server has no /presets).
                launch { runCatching { presets.refresh() } }
                launch { runCatching { brand.refresh() } }
            } catch (e: ApiException) {
                when {
                    e.isUnauthorized -> auth.expire()
                    e.isPendingApproval -> auth.onPendingApproval()
                    else -> _ui.update { it.copy(loading = false, error = e.message) }
                }
            } catch (e: IOException) {
                _ui.update { it.copy(loading = false, error = "Cannot reach the server: ${e.message}") }
            }
        }
    }

    private val dismissedStale = mutableSetOf<String>()

    /** Looks for streams still marked live that this phone is not publishing. Silent on errors. */
    fun checkStaleStreams() {
        viewModelScope.launch {
            val items = runCatching { api.recentStreams() }.getOrNull() ?: return@launch
            // The check runs against what the controller has NOW (also during STARTING, when our own stream may already be listed as live).
            val cs = controller.ui.value
            if (cs.phase == app.streambird.android.stream.LivePhase.STARTING) return@launch
            val active = controller.currentStreamId.takeIf { cs.active }
            _ui.update { it.copy(staleStreams = app.streambird.android.stream.staleLiveStreams(items, active, dismissedStale)) }
        }
    }

    fun dismissStale(id: String) { dismissedStale.add(id); _ui.update { it.copy(staleStreams = it.staleStreams.filter { s -> s.id != id }) } }

    fun endStale(id: String) {
        viewModelScope.launch {
            try {
                api.endStream(id)
                _ui.update { it.copy(staleStreams = it.staleStreams.filter { s -> s.id != id }, staleNote = "Stream ended.") }
            } catch (e: Exception) {
                _ui.update { it.copy(staleNote = "Could not end it: ${e.message}") }
            }
        }
    }

    fun toggle(id: String) = _ui.update { s ->
        val max = s.limits?.maxDestinations ?: 1
        when {
            id in s.selected -> s.copy(selected = s.selected - id, limitNote = null)
            canSelectMore(s.selected.size, max) -> s.copy(selected = s.selected + id, limitNote = null)
            else -> s.copy(limitNote = "Your ${s.limits?.planName ?: "plan"} allows $max destination${if (max == 1) "" else "s"} at a time.")
        }
    }

    fun update(f: (StudioUi) -> StudioUi) = _ui.update(f)

    val brandState get() = brand.state
    val brandSelectedKitId get() = brand.selectedKitId

    /** Name of the brand kit that would apply to [destinations] (the explicit choice or the automatic one); null when there is none. */
    fun kitName(destinations: Collection<String>, state: app.streambird.android.brand.BrandState, @Suppress("UNUSED_PARAMETER") selectedKitId: String?): String? =
        brand.effectiveKit(destinations, state)?.name

    private fun choices(s: StudioUi) = SetupChoices(s.selected, s.tier, s.orientation, brand.selectedKitId.value, s.tickerText)

    private fun limits(s: StudioUi): SetupLimits {
        val b = brand.state.value
        return SetupLimits(
            connectionIds = s.connections.map { it.id }.toSet(),
            maxDestinations = s.limits?.maxDestinations ?: 1,
            allowedTiers = s.allowedTiers,
            brandAllowed = s.brandAllowed,
            knownKitIds = if (b.loaded && !b.blocked) b.kits.map { it.id }.toSet() else null,
        )
    }

    /** The card shown when the server has no presets (or none yet): today's setup as one preset. */
    fun implicitPreset(): app.streambird.android.data.Preset {
        val s = _ui.value
        return implicitDefaultPreset(choices(s), s.connections, s.limits?.maxDestinations ?: 1, s.brandAllowed)
    }

    /** Makes [p] the current setup (destinations, quality, shape, brand kit, ticker). [p] = null starts a new, empty-named preset from the implicit default. */
    fun openPreset(p: app.streambird.android.data.Preset?) {
        val preset = p ?: implicitPreset()
        val s = _ui.value
        val applied = applyPresetConfig(preset.config, choices(s), limits(s))
        brand.select(applied.choices.brandKitId)
        _ui.update {
            it.copy(
                selected = applied.choices.selected, tier = applied.choices.tier, orientation = applied.choices.orientation,
                tickerText = applied.choices.tickerText, limitNote = null,
                logoChoice = if (it.brandAllowed) LogoChoice.BRAND else it.logoChoice,
                activePreset = preset.takeUnless { x -> x.isImplicit || p == null },
                presetNote = applied.notes.joinToString(" ").ifEmpty { null },
            )
        }
    }

    /** Saves the current setup as a new preset (or over the open one when [overwrite]). Returns true on success. */
    fun savePreset(name: String, overwrite: Boolean, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val s = _ui.value
            val saved = presets.save(if (overwrite) s.activePreset?.id else null, name, choicesToConfig(choices(s)))
            if (saved != null) _ui.update { it.copy(activePreset = saved) }
            onDone(saved != null)
        }
    }

    fun deletePreset(id: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = presets.delete(id)
            if (ok) _ui.update { if (it.activePreset?.id == id) it.copy(activePreset = null) else it }
            onDone(ok)
        }
    }

    fun goLive() {
        val s = _ui.value
        settings.tickerText = app.streambird.android.media.Ticker.sanitize(s.tickerText); settings.tickerSize = s.tickerSize.name
        controller.start(
            GoLiveRequest(
                title = s.title, visibility = s.visibility, destinationIds = s.selected, tier = s.tier, orientation = s.orientation, priority = s.priority,
                frontCamera = s.frontCamera, h264HighProfile = s.h264HighProfile, forceKeyFrames = s.forceKeyFrames,
                logoChoice = if (s.logoChoice == LogoChoice.BRAND && !s.brandAllowed) LogoChoice.NONE else s.logoChoice,
                tickerText = app.streambird.android.media.Ticker.sanitize(s.tickerText), tickerSize = s.tickerSize,
                brandKitId = brand.selectedKitId.value,
                adOverrides = s.adOverrides, adsShrinkOverride = s.adsShrink, useKitAds = s.logoChoice == LogoChoice.BRAND && s.brandAllowed, freePlan = s.limits?.watermark == true, thumbnailUri = s.thumbnailUri,
                thumbnailKitId = app.streambird.android.brand.KitThumbnail.requestKitId(
                    brand.selectedKitId.value, brand.state.value.kits, usingKit = s.logoChoice == LogoChoice.BRAND && s.brandAllowed, ownPicked = s.thumbnailUri != null,
                ),
                liveLinks = s.connections.filter { it.id in s.selected }.associate { it.id to it.liveLinkUrl },
            ),
        )
        // One tap on a preset's Go live also records the use (most recent first on the home screen). Never blocks or fails the stream.
        s.activePreset?.takeUnless { it.isImplicit }?.let { p -> viewModelScope.launch { presets.markUsed(p.id) } }
    }

    val baseUrl: String get() = settings.baseUrl

    fun logout() { viewModelScope.launch { auth.logout() } }
}
