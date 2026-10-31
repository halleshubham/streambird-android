package app.streambird.android.media

/**
 * What the stream controller needs from the media engine. [StreamEngine] is the real implementation; JVM tests use a fake.
 * Contract: [stop] is idempotent and safe to call from any thread/coroutine (the second call waits for the first and returns);
 * once stop was called, [republish] fails.
 */
interface EnginePort {
    suspend fun start(cfg: EngineConfig)
    suspend fun stop()
    suspend fun republish()
    fun simulateDrop()
    fun setTicker(spec: TickerSpec?)
    fun setPicturePriority(p: PicturePriority)
    /** The ad clock starts at 0 on the real go-live and keeps counting through a quiet republish. */
    fun startAdClock()
    /** Show now / Hide (null = follow the schedule). Never saved. */
    fun setAdManual(p: app.streambird.android.ads.AdPlacement, m: app.streambird.android.ads.AdManual?)
    fun switchCamera(done: (error: String?, nowFront: Boolean) -> Unit)
    val preview: PreviewHandle?
    val droppedFrames: Long
    fun dropSummary(): String
    val frontCamera: Boolean
    val hasBothCameras: Boolean
}
