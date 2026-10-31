package app.streambird.android.stream

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import app.streambird.android.R
import app.streambird.android.media.CodecProbe
import app.streambird.android.media.DeviceReading
import app.streambird.android.media.DeviceTelemetry
import app.streambird.android.media.EnginePort
import app.streambird.android.media.EngineListener
import app.streambird.android.media.NetworkWatcher
import app.streambird.android.media.StreamEngine
import app.streambird.android.media.Trigger
import app.streambird.android.whip.WhipClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything the [StreamController] needs from Android, so the controller's logic can run on the JVM with a fake. */
interface StreamEnv {
    fun startForegroundService()
    fun stopForegroundService()
    fun createEngine(listener: EngineListener): EnginePort
    fun readDevice(): DeviceReading?
    fun watchDeviceNetwork(onChange: (String) -> Unit): () -> Unit
    fun watchNetworkTriggers(onTrigger: (Trigger) -> Unit): () -> Unit
    fun logHeader(): List<Pair<String, String>>
    fun testLogo(): Bitmap?
    fun decodeLogo(bytes: ByteArray): Bitmap?
    /** Blocking: decode, scale and compress the picked image to a JPEG under 2 MB. */
    fun prepareThumbnail(uri: String, portrait: Boolean): ByteArray
}

class AndroidStreamEnv(private val context: Context) : StreamEnv {
    private val telemetry = DeviceTelemetry(context)
    private val whipHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    override fun startForegroundService() { ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java)) }
    override fun stopForegroundService() { context.stopService(Intent(context, StreamingService::class.java)) }
    override fun createEngine(listener: EngineListener): EnginePort = StreamEngine(context, WhipClient(whipHttp), listener)
    override fun readDevice(): DeviceReading? = runCatching { telemetry.read() }.getOrNull()
    override fun watchDeviceNetwork(onChange: (String) -> Unit): () -> Unit = telemetry.watchNetwork(onChange)
    override fun watchNetworkTriggers(onTrigger: (Trigger) -> Unit): () -> Unit {
        val w = NetworkWatcher(context, onTrigger).also { it.start() }
        return { w.stop() }
    }

    override fun logHeader() = listOf(
        "app" to "StreamBird Android ${app.streambird.android.BuildConfig.VERSION_NAME}",
        "device" to "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})",
        "codecs" to CodecProbe.summary(),
    )

    override fun testLogo(): Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.logo_bird)

    /** Bounded in both dimensions (the GL texture limit is at least 2048 on every supported phone; 1024 is plenty for a logo). */
    override fun decodeLogo(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LOGO_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val m = maxOf(bmp.width, bmp.height)
        if (m <= 2048) return bmp
        val k = 2048f / m
        return Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true).also { bmp.recycle() }
    }

    override fun prepareThumbnail(uri: String, portrait: Boolean): ByteArray = ThumbnailPreparer.prepare(context, Uri.parse(uri), portrait).bytes

    private companion object { const val MAX_LOGO_SIDE = 1024 }
}
