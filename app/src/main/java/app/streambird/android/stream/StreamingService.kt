package app.streambird.android.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.streambird.android.MainActivity
import app.streambird.android.R
import app.streambird.android.media.formatBitrate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (camera|microphone) so the stream survives screen lock and the app going to the background.
 * It owns no media: [StreamController] does. It shows the notification, holds CPU/Wi-Fi wake locks and stops itself
 * when the stream is over.
 */
@AndroidEntryPoint
class StreamingService : Service() {
    @Inject lateinit var controller: StreamController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var collector: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: a service started with startForegroundService() must call startForeground() promptly,
        // even when it is about to stop again (fast failure, or End pressed with nothing running).
        ensureChannel()
        val n = notification("Starting the stream...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, n)
        if (intent?.action == ACTION_END) {
            controller.end()
            if (!controller.ui.value.active) stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null && wifi == null) acquireLocks()
        if (collector == null) collector = scope.launch {
            controller.ui.collect { ui ->
                if (!ui.active) { stopSelf(); return@collect }  // the stream is over (also: it failed before this service reached the foreground)
                val text = when (ui.phase) {
                    LivePhase.LIVE -> if (ui.reconnect.phaseBusy) "Reconnecting..." else "LIVE ${ui.stats?.let { "${it.width}x${it.height} ${formatBitrate((ui.kbps ?: 0.0) * 1000)}" } ?: ""}"
                    LivePhase.STARTING -> "Starting..."
                    LivePhase.ENDING -> "Ending..."
                    else -> ""
                }
                if (text.isNotEmpty()) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text.trim()))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getService(this, 1, Intent(this, StreamingService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("StreamBird")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "End stream", end)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live stream", NotificationManager.IMPORTANCE_LOW))
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streambird:stream").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "streambird:stream").apply { acquire() }
        }
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
        scope.cancel()
        // Service killed while a stream is running (user swiped it, system limit): end the stream so nothing is left half-open.
        if (controller.ui.value.active) controller.end()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "live_stream"
        const val NOTIF_ID = 42
        const val ACTION_END = "app.streambird.android.END"
    }
}
