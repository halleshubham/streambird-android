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
    fun switchCamera(done: (error: String?, nowFront: Boolean) -> Unit)
    val preview: PreviewHandle?
    val droppedFrames: Long
    fun dropSummary(): String
    val frontCamera: Boolean
    val hasBothCameras: Boolean
}
