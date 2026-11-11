package im.cue.saltaudio.mcp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** 内置歌曲读取：MediaStore 音频扫描，宿主无需实现任何曲库接口 */
internal object McpTrackScanner {

    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val uri: Uri
    )

    fun scan(context: Context, limit: Int = 5000): List<Track> {
        val out = ArrayList<Track>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.DURATION} > 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = try { c.getColumnIndex(MediaStore.Audio.Media.DATA) } catch (_: Exception) { -1 }
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(iId)
                val path = if (iData >= 0) c.getString(iData) else null
                // file:// 真实路径优先（宿主 UI 的 MediaItem 用 DATA→toUri()，依赖 file scheme）；DATA 不可用时退化为 content://
                val uri: Uri = if (!path.isNullOrBlank()) Uri.fromFile(java.io.File(path))
                    else ContentUris.withAppendedId(collection, id)
                out += Track(
                    id = id,
                    title = c.getString(iT) ?: "",
                    artist = c.getString(iA) ?: "",
                    album = c.getString(iAl) ?: "",
                    durationMs = c.getLong(iD),
                    uri = uri
                )
            }
        }
        return out
    }
}