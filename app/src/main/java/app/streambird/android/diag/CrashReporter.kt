package app.streambird.android.diag

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.streambird.android.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Makes a crash diagnosable without adb: a default uncaught-exception handler writes the Java stack to filesDir; on every start the
 * previous process's exit reason (ActivityManager.getHistoricalProcessExitReasons, API 30+, incl. the native tombstone summary) and
 * the last 200 events ([Diag]) are assembled into one report that the Setup screen offers to share.
 */
object CrashReporter {
    private const val JAVA_CRASH = "last-java-crash.txt"
    private const val REPORT = "pending-crash-report.txt"
    private const val HANDLED_TS = "crash-handled-ts.txt"

    fun install(context: Context) {
        val dir = context.filesDir
        Diag.init(dir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(dir, JAVA_CRASH).writeText("thread: ${t.name}\ntime: ${System.currentTimeMillis()}\n$sw")
                Diag.log("UNCAUGHT EXCEPTION on ${t.name}: $e")
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Call once at app start, BEFORE this run writes events: builds the report of the previous run if it ended badly. */
    fun collectPrevious(context: Context) {
        runCatching {
            val dir = context.filesDir
            val events = Diag.lines()
            val handled = File(dir, HANDLED_TS).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            var input: CrashReport.Input? = null

            if (Build.VERSION.SDK_INT >= 30) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
                val bad = exits.firstOrNull { CrashReport.isCrashReason(it.reason) && it.timestamp > handled }
                if (bad != null) {
                    val trace = runCatching { bad.traceInputStream?.use { String(it.readBytes().take(CrashReport.MAX_BYTES).toByteArray()) } }.getOrNull()
                    input = CrashReport.Input(version(), device(), bad.timestamp, CrashReport.reasonName(bad.reason), bad.description, trace, events)
                    File(dir, HANDLED_TS).writeText(bad.timestamp.toString())
                }
            }
            val java = File(dir, JAVA_CRASH)
            if (java.exists()) {
                val text = java.readText()
                if (input == null) input = CrashReport.Input(version(), device(), java.lastModified(), "JAVA_EXCEPTION", null, text, events)
                else input = input.copy(trace = (input.trace.orEmpty() + "\n\n== java exception ==\n" + text))
                java.delete()
            }
            if (input != null) File(dir, REPORT).writeText(CrashReport.build(input))
            Diag.log("--- app start ${version()} (${device()}) ---")
        }
    }

    fun pendingReport(context: Context): String? = runCatching { File(context.filesDir, REPORT).takeIf { it.exists() }?.readText() }.getOrNull()

    fun dismiss(context: Context) { runCatching { File(context.filesDir, REPORT).delete() } }

    /** Shares the report as a text file through the Android share sheet. */
    fun share(context: Context, report: String) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val f = File(dir, "streambird-crash-${System.currentTimeMillis() / 1000}.txt").apply { writeText(report) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "StreamBird crash report ${version()}")
            putExtra(Intent.EXTRA_TEXT, report.take(4000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun version() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"
}
