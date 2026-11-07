package app.streambird.android.brand

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.AssetLibrary
import app.streambird.android.data.BrandKit
import app.streambird.android.data.EffectiveLimits
import app.streambird.android.data.KitDraft
import app.streambird.android.data.UploadedAsset
import app.streambird.android.data.assetFileUrl
import app.streambird.android.data.assetLibrary
import app.streambird.android.data.assignBrandKit
import app.streambird.android.data.createBrandKit
import app.streambird.android.data.deleteAsset
import app.streambird.android.data.deleteBrandKit
import app.streambird.android.data.updateBrandKit
import app.streambird.android.data.StreamingFileBody
import app.streambird.android.data.uploadAssetStreaming
import app.streambird.android.ads.FrameWindowState
import app.streambird.android.ads.windowOfBytes
import app.streambird.android.media.GifLogoPlan
import app.streambird.android.media.GifTiming
import app.streambird.android.media.parseGifTiming
import app.streambird.android.media.planGifLogo
import app.streambird.android.media.pickBrandKit
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A file the user picked was refused before (or by) the upload; [message] is shown as is. */
class UploadRefused(message: String) : IOException(message)

data class BrandState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    /** Free plan (no brand library): kits are paid-plan-only. */
    val blocked: Boolean = false,
    val plan: EffectiveLimits? = null,
    val kits: List<BrandKit> = emptyList(),
    val library: AssetLibrary? = null,
) {
    /** Object storage is configured on the server (uploads possible). */
    val storageEnabled get() = library?.enabled != false
}

/** Process-wide brand state shared by the Brand screens and the Setup screen. */
@Singleton
class BrandRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiClient,
    http: OkHttpClient,
) {
    private val _state = MutableStateFlow(BrandState())
    val state: StateFlow<BrandState> = _state.asStateFlow()

    /** The kit the user chose for the next stream; null = automatic. */
    private val _selectedKitId = MutableStateFlow<String?>(null)
    val selectedKitId: StateFlow<String?> = _selectedKitId.asStateFlow()
    fun select(kitId: String?) { _selectedKitId.value = kitId }

    /** Forget everything cached (the account was deleted). */
    fun reset() { _state.value = BrandState(); _selectedKitId.value = null }

    /** Coil loader that goes through the app's OkHttpClient (session cookie + Origin) and animates GIF/WebP. */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context).okHttpClient(http).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }

    fun assetUrl(assetId: String) = api.assetFileUrl(assetId)

    // ---- checks the kit editor shows (cached per file: library files never change under the same id) ----
    private val windowCache = java.util.concurrent.ConcurrentHashMap<String, FrameWindowState>()
    private val gifTimingCache = java.util.concurrent.ConcurrentHashMap<String, GifTiming>()

    /** The clear window of an L-shaped frame picture (downloads it once). [FrameWindowState.Unreadable] when it cannot be fetched or decoded; that is not cached. */
    suspend fun frameWindow(assetId: String): FrameWindowState {
        windowCache[assetId]?.let { return it }
        return try {
            val bytes = api.assetFile(assetId)
            val state = withContext(Dispatchers.Default) {
                windowOfBytes(bytes)?.let { FrameWindowState.Found(it) as FrameWindowState } ?: FrameWindowState.NoWindow
            }
            windowCache[assetId] = state
            state
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            FrameWindowState.Unreadable((t as? ApiException)?.message ?: t.message ?: t.javaClass.simpleName)
        }
    }

    /** What the broadcast does with this GIF logo at [logoSizePx] (null = could not be checked: no note). */
    suspend fun gifLogoPlan(assetId: String, logoSizePx: Int): GifLogoPlan? {
        val cached = gifTimingCache[assetId]
        val timing = cached ?: try {
            val t = parseGifTiming(api.assetFile(assetId))
            if (t != null) gifTimingCache[assetId] = t
            t ?: return GifLogoPlan.Still("the GIF could not be read")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return null
        }
        return planGifLogo(timing, logoSizePx)
    }

    /** Loads plan, kits and library. Throws [ApiException] for 401 / pending so the caller can route it. */
    suspend fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            coroutineScope {
                val limits = api.myLimits()
                if (limits.assetStorageMb <= 0) {
                    _state.update { BrandState(loaded = true, blocked = true, plan = limits) }
                    return@coroutineScope
                }
                val k = async { api.brandKits() }
                val l = async { api.assetLibrary() }
                val kits = k.await()
                val lib = l.await()
                _state.update { BrandState(loaded = true, plan = limits, kits = kits, library = lib) }
                // A kit that no longer exists cannot stay selected.
                if (_selectedKitId.value != null && kits.none { it.id == _selectedKitId.value }) _selectedKitId.value = null
            }
        } catch (e: ApiException) {
            if (e.isUnauthorized || e.isPendingApproval) throw e
            if (e.status == 403) _state.update { it.copy(loading = false, loaded = true, blocked = true) }
            else _state.update { it.copy(loading = false, error = e.message) }
        } catch (e: IOException) {
            _state.update { it.copy(loading = false, error = "Cannot reach the server: ${e.message}") }
        }
    }

    /** The kit that would apply to a stream to [destinationIds]: the explicit choice, else the web studio's auto-apply rule. */
    fun effectiveKit(destinationIds: Collection<String>, s: BrandState = _state.value): BrandKit? =
        s.kits.firstOrNull { it.id == _selectedKitId.value }
            ?: pickBrandKit(s.kits, destinationIds, { it.isDefault }, { it.connectionIds })

    /** Creates or updates a kit and then syncs which channels use it (PUT /brand-kits/connections/:id; null = account default). */
    suspend fun saveKit(original: BrandKit?, draft: KitDraft, channels: Set<String>): BrandKit {
        val saved = try {
            if (original == null) api.createBrandKit(draft) else api.updateBrandKit(original, draft)
        } catch (e: ApiException) {
            // An older server rejects the new fields with a validator message: say it in plain words.
            throw KitCompat.saveError(e.status, e.message, draft)?.let { ApiException(e.status, it) } ?: e
        }
        val before = original?.connectionIds?.toSet() ?: emptySet()
        for (c in channels - before) api.assignBrandKit(c, saved.id)
        for (c in before - channels) api.assignBrandKit(c, null)
        refresh()
        return saved
    }

    suspend fun deleteKit(id: String) {
        api.deleteBrandKit(id)
        if (_selectedKitId.value == id) _selectedKitId.value = null
        refresh()
    }

    suspend fun deleteAsset(id: String) {
        api.deleteAsset(id) // 409 "used by the brand kits ..." comes back as the ApiException message
        refresh()
    }

    /**
     * Checks the picked image like the backend would (brand images <= 8 MB and 8192 px; a thumbnail is a JPG/PNG <= 2 MB, >= 640 px wide; GIF only for
     * logo and ad) and uploads it. The file is NOT loaded: its size comes from the provider, only its first 512 KB are read to find format and size, and the
     * upload streams it from the Uri in 64 KB steps ([StreamingFileBody]) over the upload client with stall-based timeouts (60 s write, 120 s answer).
     */
    suspend fun uploadPicked(uri: Uri, kind: String = "logo"): UploadedAsset = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val open = { cr.openInputStream(uri) }
        val ready = when (val p = prepareUpload(open, declaredSize(cr, uri), kind)) {
            is PreparedUpload.Refused -> throw UploadRefused(p.message)
            is PreparedUpload.Ready -> p
        }
        val info = ready.info
        val display = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val fileName = (display ?: kind).let { if (it.contains('.')) it else "$it.${info.format.ext}" }
        val body = StreamingFileBody(info.format.mime, ready.size) { open() ?: throw IOException("Cannot open the chosen image.") }
        val up = api.uploadAssetStreaming(body, fileName, kind = kind, name = display?.substringBeforeLast('.')?.take(120))
        refresh()
        up
    }

    /** Size in bytes from the content provider (asset descriptor first, then the OpenableColumns.SIZE column); -1 when it does not say. */
    private fun declaredSize(cr: android.content.ContentResolver, uri: Uri): Long {
        val fd = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
        if (fd != null && fd >= 0) return fd
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }
        }.getOrNull() ?: -1L
    }
}
