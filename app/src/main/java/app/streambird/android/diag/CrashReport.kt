package app.streambird.android.diag

/** Pure report assembly (JVM-tested). */
object CrashReport {
    const val MAX_BYTES = 64 * 1024

    data class Input(
        val appVersion: String,
        val device: String,
        val whenMs: Long?,
        /** e.g. "CRASH_NATIVE", "CRASH", "ANR" or "JAVA_EXCEPTION". */
        val reason: String,
        val description: String?,
        /** Native tombstone summary or Java stack. */
        val trace: String?,
        val events: List<String>,
    )

    /** Keeps the head and the tail of [s] when it exceeds [maxBytes] (UTF-8 bytes approximated by chars; the trace is ASCII). */
    fun trim(s: String, maxBytes: Int = MAX_BYTES): String =
        if (s.length <= maxBytes) s else s.take(maxBytes / 4) + "\n...[trimmed ${s.length - maxBytes} chars]...\n" + s.takeLast(maxBytes - maxBytes / 4)

    fun build(i: Input, nowMs: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val head = buildString {
            appendLine("StreamBird crash report")
            appendLine("app: ${i.appVersion}")
            appendLine("device: ${i.device}")
            appendLine("reason: ${i.reason}")
            i.whenMs?.let { appendLine("time: ${fmt.format(java.util.Date(it))}") }
            appendLine("report created: ${fmt.format(java.util.Date(nowMs))}")
            i.description?.takeIf { it.isNotBlank() }?.let { appendLine("description: $it") }
            appendLine()
            appendLine("== last ${i.events.size} events before the crash ==")
            i.events.forEach { appendLine(it) }
            appendLine()
            appendLine("== trace ==")
            append(i.trace?.takeIf { it.isNotBlank() } ?: "(none available)")
        }
        // Events are the most useful part: if it is too long, trim the trace (the end of the report) first.
        return trim(head)
    }

    /** Which historical exit reasons count as "closed unexpectedly": a native/Java crash, an ANR, or a failed init. */
    fun isCrashReason(reason: Int): Boolean = reason in setOf(5 /*CRASH*/, 6 /*CRASH_NATIVE*/, 7 /*ANR*/, 8 /*INITIALIZATION_FAILURE*/)

    fun reasonName(reason: Int): String = when (reason) {
        5 -> "CRASH"; 6 -> "CRASH_NATIVE"; 7 -> "ANR"; 8 -> "INITIALIZATION_FAILURE"
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 9 -> "PERMISSION_CHANGE"; 10 -> "EXCESSIVE_RESOURCE_USAGE"; 11 -> "USER_REQUESTED"; 12 -> "USER_STOPPED"; 13 -> "DEPENDENCY_DIED"
        else -> "OTHER($reason)"
    }
}
