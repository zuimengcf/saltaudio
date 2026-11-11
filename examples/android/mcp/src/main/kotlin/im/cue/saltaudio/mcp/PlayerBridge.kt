package im.cue.saltaudio.mcp

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 万能桥接层：MCP server 与宿主播放器之间的唯一边界。
 *
 * server 只认这个接口；任何播放器（media3 / media-compat / 其他）只要
 * 提供一个 Bridge 实现即可接入，server 协议层零改动。
 *
 * 线程约束由各实现内部自理：Media3Bridge 的所有调用必须在主线程，
 * 用 latch 同步回读——这些坑不外漏。
 */
interface PlayerBridge {
    /** 连接宿主播放器。返回 false 表示暂不可用（宿主未启动/服务未运行）。 */
    suspend fun connect(): Boolean

    /** 播放状态快照。仅在 connect 成功后有意义。 */
    fun snapshot(): PlaybackSnapshot

    fun togglePlayPause()
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)

    /** shuffle/repeat 传 null 表示不改那一项 */
    fun setMode(shuffle: Boolean?, repeat: String?)

    /** 按曲库条目设入队列并从 startIndex 播放 */
    fun playQueue(items: List<TrackRef>, startIndex: Int)

    /** 追加到队尾 */
    fun appendToQueue(items: List<TrackRef>)

    /** 插入到当前曲目之后 */
    fun insertNext(items: List<TrackRef>)

    /** 按索引移除队列项 */
    fun removeQueueItem(index: Int)

    /** 移动队列项 */
    fun moveQueueItem(fromIndex: Int, toIndex: Int)

    /** 播放器级音量（0.0–1.0，不影响系统媒体音量） */
    fun getVolume(): Float

    fun setVolume(volume: Float)

    fun release()
}

/** 曲目引用：跨 Bridge 的通用曲目描述 */
data class TrackRef(
    val id: Long,
    val uri: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long = 0L
)

/** 播放状态快照（纯数据，无播放器类型依赖） */
data class PlaybackSnapshot(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val currentIndex: Int,
    val queueSize: Int,
    val shuffle: Boolean,
    val repeatMode: String,
    val currentTitle: String?,
    val currentArtist: String?
)

// ==================================================================
// media3 实现：包住 MediaController，主线程约束封死在这里
// ==================================================================

/** 宿主接入最常用的便捷工厂：连到宿主自己的 MediaSessionService（media3） */
object HostController {
    fun forService(context: Context, serviceName: Class<*>): suspend (Context) -> PlayerBridge? {
        val app = context.applicationContext
        return {
            runCatching {
                val token = SessionToken(app, android.content.ComponentName(app, serviceName))
                // buildAsync 在主线程发起（media3 要求），但绝不能在主线程 future.get() ——
                // 连接建立需要主线程处理 binder 回调，阻塞等待会自锁导致宿主 ANR。
                val future = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    MediaController.Builder(app, token).buildAsync()
                }
                val controller = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    future.get(8, java.util.concurrent.TimeUnit.SECONDS)
                }
                Media3Bridge(controller, app)
            }.getOrNull()
        }
    }
}

class Media3Bridge(private val c: MediaController, context: Context?) : PlayerBridge {

    private val appContext: Context? = context?.applicationContext

    override suspend fun connect(): Boolean {
        // 连接后立即从源播放器当前队列自学格式偏好（URI scheme / mediaId 正负），
        // 供后续把通用 TrackRef 转成源期望格式（中间桥接层）。
        mainOp("learn") { BridgeAdaptor.learnFromHost(c) }
        return true
    }

    override fun snapshot(): PlaybackSnapshot = mainCall("snapshot") {
        c.snapshot()
    } ?: PlaybackSnapshot(false, 0, 0, 0, 0, false, "OFF", null, null)

    override fun togglePlayPause() = mainOp("toggle_play_pause") {
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }
    override fun play() = mainOp("play") {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }
    override fun pause() = mainOp("pause") { c.pause() }

    override fun next() = mainOp("next") { c.seekToNext() }

    override fun previous() = mainOp("previous") {
        if (c.currentPosition > 3_000) c.seekTo(0L) else c.seekToPrevious()
    }

    override fun seekTo(positionMs: Long) = mainOp("seek") { c.seekTo(positionMs) }

    override fun setMode(shuffle: Boolean?, repeat: String?) = mainOp("set_playback_mode") {
        shuffle?.let { c.shuffleModeEnabled = it }
        when (repeat) {
            "ONE" -> c.repeatMode = Player.REPEAT_MODE_ONE
            "ALL" -> c.repeatMode = Player.REPEAT_MODE_ALL
            "OFF" -> c.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    override fun playQueue(items: List<TrackRef>, startIndex: Int) = mainOp("play_tracks") {
        c.setMediaItems(items.map { BridgeAdaptor.toHostMediaItem(appContext, it) }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    override fun appendToQueue(items: List<TrackRef>) = mainOp("append_to_queue") {
        c.addMediaItems(items.map { BridgeAdaptor.toHostMediaItem(appContext, it) })
    }

    override fun insertNext(items: List<TrackRef>) = mainOp("insert_next") {
        val insertPos = c.currentMediaItemIndex + 1
        c.addMediaItems(insertPos, items.map { BridgeAdaptor.toHostMediaItem(appContext, it) })
    }

    override fun removeQueueItem(index: Int) = mainOp("remove_queue_item") {
        c.removeMediaItem(index)
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) = mainOp("move_queue_item") {
        c.moveMediaItem(fromIndex, toIndex)
    }

    override fun getVolume(): Float = mainCall("get_volume") { c.volume } ?: 1f

    override fun setVolume(volume: Float) = mainOp("set_volume") {
        c.volume = volume.coerceIn(0f, 1f)
    }

    override fun release() {
        main.post { runCatching { c.release() } }
    }

    // ---------- 主线程同步执行（media3 线程约束封死在这里） ----------

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun <T> mainCall(tag: String, op: () -> T): T? {
        val result = java.util.concurrent.atomic.AtomicReference<T?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            runCatching { result.set(op()) }
                .onFailure { McpLog.log("bridge $tag failed: ${it.message}") }
            latch.countDown()
        }
        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            .onFailure { McpLog.log("bridge $tag latch error") }
        return result.get()
    }

    private fun mainOp(tag: String, op: () -> Unit) {
        mainCall(tag, op)
    }
}

// ------------------------------------------------------------------

/** 旧 PlaybackSnapshot 扩展（原 contract 内）迁移到此，media3 → 快照 */
internal fun Player.snapshot(): PlaybackSnapshot = PlaybackSnapshot(
    playing = isPlaying,
    positionMs = currentPosition.coerceAtLeast(0L),
    durationMs = if (duration > 0) duration else 0L,
    currentIndex = currentMediaItemIndex,
    queueSize = mediaItemCount,
    shuffle = shuffleModeEnabled,
    repeatMode = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> "ONE"
        Player.REPEAT_MODE_ALL -> "ALL"
        else -> "OFF"
    },
    currentTitle = currentMediaItem?.mediaMetadata?.title?.toString(),
    currentArtist = currentMediaItem?.mediaMetadata?.artist?.toString()
)
