package app.streambird.android.diag

import java.io.File

/**
 * A tiny persistent ring buffer of text lines (the last [maxLines]): appended and flushed per line so a native crash that kills the
 * process mid-stream still leaves the events before it on disk. The file is trimmed to the last [maxLines] when it grows to 2x.
 */
class RingLog(private val file: File, private val maxLines: Int = 200) {
    private var count = -1

    @Synchronized fun append(line: String) {
        try {
            val clean = line.replace('\n', ' ').replace('\r', ' ').take(500)
            if (count < 0) count = if (file.exists()) file.useLines { it.count() } else 0
            file.parentFile?.mkdirs()
            java.io.FileOutputStream(file, true).use { it.write((clean + "\n").toByteArray()); it.flush() }
            count++
            if (count >= maxLines * 2) trim()
        } catch (_: Throwable) { /* diagnostics must never break the app */ }
    }

    @Synchronized fun lines(): List<String> = try {
        if (file.exists()) file.readLines().takeLast(maxLines) else emptyList()
    } catch (_: Throwable) { emptyList() }

    @Synchronized private fun trim() {
        val keep = file.readLines().takeLast(maxLines)
        file.writeText(keep.joinToString("\n", postfix = "\n"))
        count = keep.size
    }
}

/** Process-wide diagnostic event log (see [CrashReporter]). Safe to call before init (no-op). */
object Diag {
    @Volatile private var ring: RingLog? = null
    @Volatile var startMs = System.currentTimeMillis()

    fun init(dir: File) { ring = RingLog(File(dir, "diag-events.log")); startMs = System.currentTimeMillis() }
    fun log(text: String) { ring?.append("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $text") }
    fun lines(): List<String> = ring?.lines().orEmpty()
}
