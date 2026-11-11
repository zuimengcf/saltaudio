package im.cue.saltaudio.minipro

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import im.cue.saltaudio.mcp.HostController
import im.cue.saltaudio.mcp.McpControlConfig
import im.cue.saltaudio.mcp.McpControlServer
import im.cue.saltaudio.mcp.McpLog
import im.cue.saltaudio.mcp.McpTrackScannerAccess
import java.util.concurrent.Executors

/**
 * 最简示例播放器：空 MediaSessionService + 一行 MCP 注入 + 基础播放页面。
 * 页面通过 MediaController 直连服务，与 MCP 控制端操作同一个播放器实例。
 * 功能：播放控制 / 进度拖动 / 播放模式切换 / MCP 随机歌单 / 曲库列表 / MCP 日志实时查看。
 */
class MainActivity : AppCompatActivity() {

    private var controller: MediaController? = null
    private val mainExecutor = Executors.newSingleThreadExecutor()
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var statusView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeText: TextView
    private lateinit var timeView: TextView
    private lateinit var modeBtn: ImageButton
    private lateinit var queueBtn: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var listHost: LinearLayout
    private lateinit var logView: TextView
    private var libTracks: List<McpTrackScannerAccess.Track> = emptyList()

    /** 用户正在拖动进度条时，ticker 不回写进度，避免拖动被回弹 */
    @Volatile private var userSeeking = false
    /** 用户正在拖动音量条时，ticker 不回写音量，避免拖动被回弹 */
    @Volatile private var userVolumeDrag = false
    /** false=曲库列表 true=播放队列 */
    private var queueMode = false
    /** 队列重建去重：只有 count 或 currentIndex 变化才重建，避免吞掉点击 */
    private var lastQueueCount = -1
    private var lastQueueIndex = -1
    /** 队列按钮旁的列表标题，随模式切换 */
    private lateinit var listTitle: TextView

    private val ticker = object : Runnable {
        override fun run() {
            try {
                syncUi()
                if (queueMode) refreshListMode()
                syncLog()
            } catch (_: Exception) { /* ticker 永不中断 */ }
            seekBar.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== 注入 MCP 独立控制系统 =====
        McpControlServer.init(
            applicationContext,
            McpControlConfig(
                bridgeFactory = HostController.forService(this, MiniService::class.java),
                port = 18790,
                accessToken = null
            )
        )
        McpControlServer.start()
        // =================================

        requestAudioPermission()

        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        titleView = TextView(this).apply { textSize = 20f; setPadding(pad, (48 * dp).toInt(), pad, 0) }
        artistView = TextView(this).apply { textSize = 14f; alpha = 0.7f; setPadding(pad, (8 * dp).toInt(), pad, 0) }
        statusView = TextView(this).apply { textSize = 12f; alpha = 0.5f; setPadding(pad, (8 * dp).toInt(), pad, 0) }

        timeView = TextView(this).apply {
            textSize = 12f; alpha = 0.6f
            setPadding(pad, (4 * dp).toInt(), pad, 0)
        }

        seekBar = SeekBar(this).apply {
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val c = controller ?: return
                        if (c.duration > 0) timeView.text = "${fmt(c.duration * progress / 100L)} / ${fmt(c.duration)}"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userSeeking = false
                    val c = controller ?: return
                    if (c.duration > 0) {
                        val target = c.duration * (sb?.progress ?: 0) / 100L
                        c.seekTo(target)
                        timeView.text = "${fmt(target)} / ${fmt(c.duration)}"
                        seekBar.progress = sb?.progress ?: 0
                    }
                }
            })
        }

        // ---- 音量条：🔉 ---- 滑条 ---- 百分比 ----
        volumeBar = SeekBar(this).apply {
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val c = controller ?: return
                        c.volume = (progress / 100f).coerceIn(0f, 1f)
                        volumeText.text = "$progress%"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { userVolumeDrag = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userVolumeDrag = false
                    val c = controller ?: return
                    c.volume = ((sb?.progress ?: 100) / 100f).coerceIn(0f, 1f)
                    volumeText.text = "${sb?.progress ?: 100}%"
                }
            })
        }
        volumeText = TextView(this).apply {
            textSize = 12f; alpha = 0.6f
            text = "100%"
            setPadding(0, 0, pad, 0)
        }
        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, (4 * dp).toInt(), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "🔉"; textSize = 16f
                setPadding(0, 0, (8 * dp).toInt(), 0)
            })
            addView(volumeBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(volumeText)
        }

        fun btn(resId: Int, desc: String, onClick: (MediaController) -> Unit) = ImageButton(this).apply {
            setImageDrawable(getDrawable(resId))
            background = null
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            contentDescription = desc
            setOnClickListener { controller?.let(onClick) }
        }

        playBtn = ImageButton(this).apply {
            background = null
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setOnClickListener {
                val ctrl = controller
                if (ctrl != null) { if (ctrl.isPlaying) ctrl.pause() else ctrl.play() }
            }
        }
        // 初始给一个播放图标，连接后由 syncUi 按 isPlaying 切换
        playBtn.setImageDrawable(getDrawable(R.drawable.ic_play))

        // 队列按钮（图标）：切换列表区显示「播放队列」（MCP play_tracks 设入的当前队列）
        queueBtn = ImageButton(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_queue))
            background = null
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            contentDescription = "队列"
            setOnClickListener {
                queueMode = !queueMode
                queueBtn.setImageDrawable(getDrawable(if (queueMode) R.drawable.ic_close else R.drawable.ic_queue))
                McpLog.log("ui: queue view ${if (queueMode) "on" else "off"}")
                refreshListMode()
            }
        }

        // 播放模式（图标）：顺序 → 循环 → 单曲 → 随机，循环切换
        modeBtn = ImageButton(this).apply {
            background = null
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            contentDescription = "播放模式"
            setOnClickListener {
                val c = controller ?: return@setOnClickListener
                val next = when (modeLabel()) {
                    "顺序" -> "循环"; "循环" -> "单曲"; "单曲" -> "随机"; else -> "顺序"
                }
                applyMode(next)
                syncModeBtn()
                McpLog.log("ui: mode -> $next")
            }
        }
        syncModeBtn()

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, 0)
            addView(btn(R.drawable.ic_prev, "上一首") { it.seekToPrevious() })
            addView(playBtn)
            addView(btn(R.drawable.ic_next, "下一首") { it.seekToNext() })
            addView(modeBtn)
            addView(queueBtn)
        }

        val hint = TextView(this).apply {
            textSize = 11f; alpha = 0.4f
            setPadding(pad, (12 * dp).toInt(), pad, 0)
            text = "点击列表歌曲即播 · MCP 控制端口 18790（/mcp）"
        }

        // 外层可滚动容器：上方播放器 + 下方曲库列表 + MCP 日志
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
val logTitle = TextView(this).apply {
            textSize = 14f; setPadding(pad, (20 * dp).toInt(), pad, 0)
            text = "MCP 日志"
        }

        listTitle = TextView(this).apply {
            textSize = 16f; setPadding(pad, (20 * dp).toInt(), pad, 0)
            text = "曲库（加载中…）"
        }


        logView = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF888888.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, (8 * dp).toInt(), pad, (24 * dp).toInt())
            text = "（暂无 MCP 操作）"
        }

        val scroll = ScrollView(this).apply { addView(listHost) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(artistView)
            addView(statusView)
            addView(timeView)
            addView(seekBar)
            addView(volumeRow)
            addView(controls)
            addView(hint)
            // MCP 日志区：播放器正下方，固定高度可滚动，始终可见
            addView(logTitle)
            addView(ScrollView(this@MainActivity).apply {
                addView(logView)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()))
            addView(listTitle)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        connect()
        loadLibrary(listTitle, pad)
        syncLog()
    }

    private fun modeLabel(): String = when (controller?.repeatMode) {
        Player.REPEAT_MODE_ONE -> if (controller?.shuffleModeEnabled == true) "随机" else "单曲"
        Player.REPEAT_MODE_ALL -> "循环"
        else -> if (controller?.shuffleModeEnabled == true) "随机" else "顺序"
    }

    private fun syncModeBtn() {
        modeBtn.setImageDrawable(getDrawable(when (modeLabel()) {
            "循环" -> R.drawable.ic_mode_repeat_all   // ↻ 全部循环
            "单曲" -> R.drawable.ic_mode_repeat_one   // 🔂 单曲重复
            "随机" -> R.drawable.ic_mode_shuffle      // 🔀 随机
            else -> R.drawable.ic_mode_order          // ➡ 顺序播放
        }))
    }

    private fun applyMode(label: String) {
        val c = controller ?: return
        when (label) {
            "顺序" -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_OFF }
            "循环" -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ALL }
            "单曲" -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ONE }
            "随机" -> { c.shuffleModeEnabled = true; c.repeatMode = Player.REPEAT_MODE_OFF }
        }
    }

    /** 按当前模式渲染列表区：曲库 或 播放队列（MCP 设入的 mediaItems） */
    private fun refreshListMode() {
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()
        if (!queueMode) {
            lastQueueCount = -1
            lastQueueIndex = -1
            listTitle.text = "曲库（${libTracks.size} 首）"
            listHost.removeAllViews()
            libTracks.forEach { t -> listHost.addView(makeTrackRow(t, libTracks, pad, dp)) }
            if (libTracks.isEmpty()) {
                listHost.addView(TextView(this).apply {
                    text = "未扫到音频文件（权限或设备无音乐）"
                    setPadding(pad, (12 * dp).toInt(), pad, 0)
                })
            }
            return
        }
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            listTitle.text = "播放队列（空）"
            listHost.removeAllViews()
            listHost.addView(TextView(this).apply {
                text = "队列空 · 等待 MCP play_tracks 或点曲库歌曲"
                setPadding(pad, (12 * dp).toInt(), pad, 0)
            })
            return
        }
        val cur = c.currentMediaItemIndex
        listTitle.text = "播放队列（${c.mediaItemCount} 首 · 第 ${cur + 1} 首）"
        if (c.mediaItemCount == lastQueueCount && cur == lastQueueIndex && listHost.childCount > 0) {
            return  // 无变化不重建，保住 ✕/条目的点击事件
        }
        lastQueueCount = c.mediaItemCount
        lastQueueIndex = cur
        listHost.removeAllViews()
        // 关闭按钮行：✕ 返回曲库
        listHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, 0, pad, 0)
            addView(ImageButton(this@MainActivity).apply {
                setImageDrawable(getDrawable(R.drawable.ic_close))
                background = null
                contentDescription = "关闭队列，返回曲库"
                setOnClickListener {
                    queueMode = false
                    queueBtn.setImageDrawable(getDrawable(R.drawable.ic_queue))
                    refreshListMode()
                }
            })
        })
        val from = 0
        val to = minOf(c.mediaItemCount, 300)
        for (i in from until to) {
            val item = c.getMediaItemAt(i)
            val m = item.mediaMetadata
            val row = TextView(this).apply {
                textSize = 14f
                setSingleLine(true)
                setPadding(pad, (10 * dp).toInt(), pad, (10 * dp).toInt())
                text = "${if (i == cur) "▶ " else "${i + 1}. "}${m.title ?: item.mediaId}\n${m.artist ?: ""}"
                alpha = if (i == cur) 1f else 0.75f
                if (i == cur) setTextColor(0xFF1E88E5.toInt())
                setOnClickListener {
                    val cc = controller ?: return@setOnClickListener
                    cc.seekTo(i, 0L)
                    if (!cc.isPlaying) cc.play()
                }
            }
            listHost.addView(row)
        }
        if (c.mediaItemCount > to) {
            listHost.addView(TextView(this).apply {
                text = "… 其余 ${c.mediaItemCount - to} 首略"
                setPadding(pad, (8 * dp).toInt(), pad, 0)
                alpha = 0.5f
            })
        }
    }

    private fun buildItem(t: McpTrackScannerAccess.Track) = MediaItem.Builder()
        .setMediaId(t.id.toString())
        .setUri(t.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title).setArtist(t.artist)
                .setAlbumTitle(t.album).build())
        .build()

    /** MediaStore 扫描曲库，点击即整表入队并从该曲开播 */
    private fun loadLibrary(listTitle: TextView, pad: Int) {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return

        mainExecutor.execute {
            val tracks = McpTrackScannerAccess.scan(applicationContext)
            libTracks = tracks
            runOnUiThread {
                refreshListMode()
            }
        }
    }

    private fun makeTrackRow(
        t: McpTrackScannerAccess.Track,
        tracks: List<McpTrackScannerAccess.Track>,
        pad: Int,
        dp: Float
    ): TextView = TextView(this).apply {
        textSize = 14f
        setSingleLine(true)
        setPadding(pad, (10 * dp).toInt(), pad, (10 * dp).toInt())
        text = "${t.title}\n${t.artist}"
        alpha = 0.9f
        setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.setMediaItems(tracks.map { tr -> buildItem(tr) }, tracks.indexOfFirst { it.id == t.id }.coerceAtLeast(0), 0L)
            c.prepare()
            c.play()
        }
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, MiniService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        // 必须用主线程 executor（MediaController 线程约束）；此前误用了自建后台 executor，
        // 回调跑在非主线程导致连接回调抛 IllegalStateException 被 runCatching 吞掉，
        // controller 恒为 null，页面（标题/进度条/按钮）全部失联。
        future.addListener({
            runCatching {
                val c = future.get()
                runOnUiThread {
                    c.addListener(listener)
                    controller = c
                    syncUi()
                    syncModeBtn()
                    refreshListMode()
                    seekBar.post(ticker)
                    McpLog.log("ui: page controller connected")
                }
            }.onFailure {
                McpLog.log("ui: page controller connect FAILED: ${it.message}")
                // 兜底：即便连接失败也要让 ticker 跑起来，权限/重试后仍可恢复
                seekBar.post(ticker)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = syncUi()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncUi()
        override fun onPlaybackStateChanged(state: Int) = syncUi()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { syncUi(); syncModeBtn() }
        override fun onRepeatModeChanged(repeatMode: Int) { syncUi(); syncModeBtn() }
    }

    private fun syncUi() {
        val c = controller ?: return
        val meta = c.currentMediaItem?.mediaMetadata
        titleView.text = meta?.title ?: "未播放"
        artistView.text = listOfNotNull(meta?.artist, meta?.albumTitle)
            .joinToString(" · ").ifEmpty { " " }
        statusView.text = when {
            c.mediaItemCount == 0 -> "队列空 · 点下方按钮生成随机歌单，或点曲库歌曲"
            c.isPlaying -> "播放中 · ${fmt(c.currentPosition)} / ${fmt(c.duration)}"
            else -> "已暂停 · ${fmt(c.currentPosition)} / ${fmt(c.duration)}"
        }
        playBtn.setImageDrawable(
            getDrawable(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        )
        timeView.text = if (c.duration > 0) "${fmt(c.currentPosition)} / ${fmt(c.duration)}" else "--:-- / --:--"
        // 真正工作的进度条：播放器位置 → 滑条；用户拖动中跳过回写
        if (!userSeeking) {
            if (c.duration > 0) {
                seekBar.max = 100
                seekBar.progress = (c.currentPosition * 100 / c.duration).toInt().coerceIn(0, 100)
            } else seekBar.progress = 0
        }
        // 音量条：外部（MCP playback_set_volume）改音量时回读同步；用户拖动中跳过回写
        if (!userVolumeDrag) {
            val v = (c.volume.coerceIn(0f, 1f) * 100).toInt()
            volumeBar.progress = v
            volumeText.text = "$v%"
        }
        syncModeBtn()
    }

    /** MCP 日志区实时刷新（环形缓冲最近 200 条，页面显示最近 6 条；限频防闪） */
    private var logTick = 0
    private fun syncLog() {
        logTick++
        if (logTick % 4 != 0) return
        val lines = McpLog.dump()
        logView.text = if (lines.isEmpty()) "（暂无 MCP 操作）" else lines.takeLast(6).joinToString("\n")
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        return "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)
    }

    override fun onDestroy() {
        seekBar.removeCallbacks(ticker)
        controller?.release()
        controller = null
        super.onDestroy()
    }

    private fun requestAudioPermission() {
        val perms = mutableListOf<String>()
        val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) {
            perms.add(audioPerm)
        }
        // Android 13+ 通知需运行时授权；未授予则 MCP 常驻通知不显示
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadLibraryAfterPermission()
        }
    }

    private fun loadLibraryAfterPermission() {
        mainExecutor.execute {
            val tracks = McpTrackScannerAccess.scan(applicationContext)
            libTracks = tracks
            runOnUiThread {
                refreshListMode()
            }
        }
    }
}