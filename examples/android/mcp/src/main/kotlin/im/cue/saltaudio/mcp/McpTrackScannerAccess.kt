package im.cue.saltaudio.mcp

/**
 * [McpTrackScanner] 的公开访问门面，供宿主 UI 复用同一个曲库扫描，
 * 保证 MCP 的 list_tracks 与宿主页面看到同一份 MediaStore 数据。
 */
object McpTrackScannerAccess {
    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val uri: android.net.Uri
    )

    fun scan(context: android.content.Context, limit: Int = 5000): List<Track> =
        McpTrackScanner.scan(context, limit).map {
            Track(it.id, it.title, it.artist, it.album, it.durationMs, it.uri)
        }
}