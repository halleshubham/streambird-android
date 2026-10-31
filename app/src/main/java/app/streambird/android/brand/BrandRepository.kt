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
import app.streambird.android.data.uploadAsset
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

    /** Coil loader that goes through the app's OkHttpClient (session cookie + Origin) and animates GIF/WebP. */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context).okHttpClient(http).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }

    fun assetUrl(assetId: String) = api.assetFileUrl(assetId)

    /** Server origin, for links to the website (billing). */
    val serverUrl: String get() = assetUrl("x").substringBefore("/api/")

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
        val saved = if (original == null) api.createBrandKit(draft) else api.updateBrandKit(original, draft)
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

    /** Reads the picked image (<= 4 MB), checks it like the backend would, uploads it (kind logo or ad). */
    suspend fun uploadPicked(uri: Uri, kind: String = "logo"): UploadedAsset = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val bytes = (cr.openInputStream(uri) ?: throw UploadRefused("Cannot open the chosen image.")).use { readLimited(it, MAX_ASSET_BYTES + 1) }
        val info = when (val c = checkAssetUpload(bytes, allowGif = true)) {
            is UploadCheck.Refused -> throw UploadRefused(c.message)
            is UploadCheck.Ok -> c.info
        }
        val display = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val fileName = (display ?: "logo").let { if (it.contains('.')) it else "$it.${info.format.ext}" }
        val up = api.uploadAsset(bytes, info.format.mime, fileName, kind = kind, name = display?.substringBeforeLast('.')?.take(120))
        refresh()
        up
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
