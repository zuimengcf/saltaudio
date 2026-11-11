package im.cue.saltaudio.mcp

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import java.io.File

/**
 * 中间桥接层（Adaptor）：插在「MCP 功能层」与「源播放器（宿主桥）」之间。
 *
 * 职责：自动识别**源播放器**对数据格式的具体假设，并自动把 MCP 功能层
 * 提供的通用 TrackRef 转换成源播放器期望的格式。MCP 功能层不需要知道
 * 源播放器有什么脾气，源播放器也不会因为格式不符而崩溃。
 *
 * 分层（本文件存在的意义）：
 *   MCP 功能层（协议/工具，无问题）
 *        │  通用表示 TrackRef(id, uri, title, ...)
 *        ▼
 *   中间桥接层（本对象：自动识别 + 自动转换）
 *        │  源播放器期望表示（file:// + 正数 id 等）
 *        ▼
 *   源播放器（MediaLibraryService / MediaSessionService，如 MiniService / GramophonePlaybackService）
 *
 * 「自动识别」而非「硬编码」的原理：
 *   源播放器同时服务宿主 UI 与 MCP 两个 MediaController；宿主 UI 通过它构造队列时
 *   产出的 MediaItem 一定符合源播放器/宿主期望的格式。因此读取**源播放器当前队列
 *   里已有的 MediaItem**（由 `learnFromHost` 完成），就是最可靠的“期望格式”老师。
 *   换任何源播放器都成立，无需人工配置。
 *
 * 已识别的两类源假设（均真实踩过坑）：
 *   ① URI scheme：部分宿主 UI 对 queue 项做 `uri.toFile()`，必须 file://；
 *      给 content:// 会 IllegalArgumentException → 宿主闪退。
 *   ② mediaId 正负：有的源用 MediaStore `_ID`（正数），有的用路径哈希（负数），
 *      混用会致队列定位/去重错乱。
 *
 * 设计铁律：全部失败安全降级（原样返回），绝不让本层成为新的崩溃源。
 *
 * 反哺来源：org.akanework.gramophone.mcp.BridgeAdaptor（在 MusicPlayer6/Gramophone 上验证通过）
 */
object BridgeAdaptor {

    /** 从源播放器当前队列学到的格式偏好。 */
    data class HostProfile(
        /** 源期望的 uri scheme：file / content / http / https / ...；null=未知 */
        val uriScheme: String? = null,
        /** 源 mediaId 是否为正数；null=无法判定 */
        val idPositive: Boolean? = null,
    ) {
        companion object { val UNKNOWN = HostProfile(null, null) }
    }

    @Volatile
    var profile: HostProfile = HostProfile.UNKNOWN
        private set

    /**
     * 从源播放器当前队列自学格式偏好。
     * 队列空时保留已学到 profile（不退化）。
     */
    fun learnFromHost(controller: MediaController) {
        try {
            if (controller.mediaItemCount <= 0) return
            val mi = controller.getMediaItemAt(0)
            val scheme = mi.localConfiguration?.uri?.scheme
            val idNum = mi.mediaId?.toLongOrNull()
            val idPositive = idNum?.let { it >= 0 }
            if (scheme != null || idPositive != null) {
                profile = HostProfile(
                    uriScheme = scheme ?: profile.uriScheme,
                    idPositive = idPositive ?: profile.idPositive,
                )
            }
        } catch (_: Throwable) { /* 学不到就保持现状 */ }
    }

    /** 手动注入/覆盖偏好（供无法自学的场景）。传 null 表示该维度不覆盖。 */
    fun override(uriScheme: String? = null, idPositive: Boolean? = null) {
        profile = HostProfile(
            uriScheme = uriScheme ?: profile.uriScheme,
            idPositive = idPositive ?: profile.idPositive,
        )
    }

    // ------------------------------------------------------------------
    // 转换（对外主入口；全部失败安全降级为原值）
    // ------------------------------------------------------------------

    /** 把原始 URI 转成源期望的 scheme。content:// → file:// 需 context 反查。 */
    fun toHostUri(context: Context?, rawUri: String?): String? {
        if (rawUri.isNullOrBlank()) return rawUri
        val wanted = profile.uriScheme ?: return rawUri
        val parsed = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return rawUri
        if (parsed.scheme == wanted) return rawUri
        return if (wanted == "file") contentToFile(context, parsed) ?: rawUri else rawUri
    }

    /** 把 mediaId 转成源期望的「正/负」约定。 */
    fun toHostMediaId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return rawId
        val pos = profile.idPositive ?: return rawId
        val num = rawId.toLongOrNull() ?: return rawId
        return when {
            pos && num < 0 -> num.toString().removePrefix("-") // 升为正
            !pos && num >= 0 -> "-$num"                          // 降为负
            else -> rawId
        }
    }

    /** 把一个通用 TrackRef 转换成「源期望格式」的 media3 MediaItem。 */
    fun toHostMediaItem(context: Context?, t: TrackRef): MediaItem {
        val uri = toHostUri(context, t.uri)
        val id = toHostMediaId(t.id.toString())
        return MediaItem.Builder()
            .setMediaId(id ?: "")
            .setUri(uri?.let { Uri.parse(it) })
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(t.title ?: "")
                .setArtist(t.artist ?: "")
                .setAlbumTitle(t.album ?: "")
                .build())
            .build()
    }

    // ------------------------------------------------------------------
    // content:// → file://（用 MediaStore.DATA 反查真实文件路径）
    // ------------------------------------------------------------------
    private fun contentToFile(context: Context?, uri: Uri): String? {
        if (context == null) return null
        return try {
            var path: String? = null
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idx >= 0) path = c.getString(idx)
                    }
                }
            path?.let { Uri.fromFile(File(it)).toString() }
        } catch (_: Throwable) { null }
    }
}