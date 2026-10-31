package app.streambird.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.AuthRepository
import app.streambird.android.data.EffectiveLimits
import app.streambird.android.data.PlatformConnection
import app.streambird.android.media.PicturePriority
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.media.canSelectMore
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
) : ViewModel() {
    private val _ui = MutableStateFlow(StudioUi())
    val ui: StateFlow<StudioUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
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

    fun toggle(id: String) = _ui.update { s ->
        val max = s.limits?.maxDestinations ?: 1
        when {
            id in s.selected -> s.copy(selected = s.selected - id, limitNote = null)
            canSelectMore(s.selected.size, max) -> s.copy(selected = s.selected + id, limitNote = null)
            else -> s.copy(limitNote = "Your ${s.limits?.planName ?: "plan"} allows $max destination${if (max == 1) "" else "s"} at a time.")
        }
    }

    fun update(f: (StudioUi) -> StudioUi) = _ui.update(f)

    fun goLive() {
        val s = _ui.value
        controller.start(
            GoLiveRequest(
                title = s.title, visibility = s.visibility, destinationIds = s.selected, tier = s.tier, orientation = s.orientation, priority = s.priority,
                frontCamera = s.frontCamera, h264HighProfile = s.h264HighProfile, forceKeyFrames = s.forceKeyFrames,
                logoChoice = if (s.logoChoice == LogoChoice.BRAND && !s.brandAllowed) LogoChoice.NONE else s.logoChoice,
                brandKitId = brand.selectedKitId.value, thumbnailUri = s.thumbnailUri,
                liveLinks = s.connections.filter { it.id in s.selected }.associate { it.id to it.liveLinkUrl },
            ),
        )
    }

    val baseUrl: String get() = settings.baseUrl

    fun logout() { viewModelScope.launch { auth.logout() } }
}
