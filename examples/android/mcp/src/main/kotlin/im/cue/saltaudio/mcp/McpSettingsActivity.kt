package im.cue.saltaudio.mcp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * MCP 控制面板：无宿主 UI 场景下接入 MCP 的完整设置页（由通知「设置」按钮拉起）。
 *
 * 功能：
 *  - 运行状态 / 端口 / 监听数 / 桥连接状态 / 鉴权
 *  - token 管理：复制 / 重置
 *  - 运行控制：启动 / 停止 MCP server
 *  - 播放控制：当前曲目/进度、播放/暂停、上一首/下一首、音量
 *  - 安全配置概览（只读展示当前生效值）
 *  - 最近日志查看（只读）
 *
 * token 不在界面上明文显示，只在点「复制」时写剪贴板（防旁人/截屏窥视）。
 */
class McpSettingsActivity : Activity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.mcp_settings_title)
        if (McpControlServer.configSnapshot() == null) {
            // MCP 未 init，提示并退出
            val tv = TextView(this).apply {
                text = getString(R.string.mcp_not_initialized)
                setPadding(40, 40, 40, 40)
                textSize = 15f
            }
            setContentView(tv)
            return
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private var statusTv: TextView? = null
    private var securityTv: TextView? = null
    private var logTv: TextView? = null
    private var startBtn: Button? = null
    private var stopBtn: Button? = null
    private var playbackTv: TextView? = null
    private var toggleBtn: Button? = null
    private var volumeBtn: Button? = null
    private var lanCb: CheckBox? = null
    private var loopbackCb: CheckBox? = null
    private var originCb: CheckBox? = null
    private var applySecBtn: Button? = null

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun sectionTitle(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(30, 100, 200))
            setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
        }

        // ===== 状态区 =====
        root.addView(sectionTitle(getString(R.string.mcp_sec_status)))
        statusTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }.also { root.addView(it) }

        // ===== Token 区 =====
        root.addView(sectionTitle(getString(R.string.mcp_sec_token)))
        val copyBtn = Button(this).apply {
            text = getString(R.string.mcp_copy_token)
            setOnClickListener {
                val t = McpControlServer.accessToken()
                if (t != null) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("mcp_token", t))
                    toast(getString(R.string.mcp_token_copied))
                    McpLog.log("access token copied from settings page")
                } else {
                    toast(getString(R.string.mcp_no_token))
                }
            }
        }
        val resetBtn = Button(this).apply {
            text = getString(R.string.mcp_reset_token)
            setOnClickListener {
                McpControlServer.resetAccessToken()
                McpControlServer.refreshStatusNotification()
                toast(getString(R.string.mcp_token_reset))
                refresh()
            }
        }
        val tokenRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val resetLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        resetLp.leftMargin = (8 * density).toInt()
        tokenRow.addView(copyBtn, copyLp)
        tokenRow.addView(resetBtn, resetLp)
        root.addView(tokenRow)

        // ===== 运行控制 =====
        root.addView(sectionTitle(getString(R.string.mcp_sec_runtime)))
        startBtn = Button(this).apply {
            text = getString(R.string.mcp_start)
            setOnClickListener { McpControlServer.start(); toast(getString(R.string.mcp_started)); refresh() }
        }
        stopBtn = Button(this).apply {
            text = getString(R.string.mcp_stop)
            setOnClickListener { McpControlServer.stop(); toast(getString(R.string.mcp_stopped)); refresh() }
        }
        val ctrlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrlRow.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stopBtn?.let {
            ctrlRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (8 * density).toInt()
            })
        }
        root.addView(ctrlRow)

        // ===== 播放控制 =====
        root.addView(sectionTitle(getString(R.string.mcp_sec_playback)))
        playbackTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }.also { root.addView(it) }
        val playRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val playPrev = Button(this).apply {
            text = getString(R.string.mcp_prev)
            setOnClickListener { McpControlServer.playbackPrevious(); refresh() }
        }
        toggleBtn = Button(this).apply {
            text = getString(R.string.mcp_play_pause)
            setOnClickListener { McpControlServer.playbackToggle(); refresh() }
        }
        val playNext = Button(this).apply {
            text = getString(R.string.mcp_next)
            setOnClickListener { McpControlServer.playbackNext(); refresh() }
        }
        val pl = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        playRow.addView(playPrev, pl)
        toggleBtn?.let { playRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (6 * density).toInt() }) }
        playNext?.let { playRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (6 * density).toInt() }) }
        root.addView(playRow)
        volumeBtn = Button(this).apply {
            text = getString(R.string.mcp_volume_btn)
            setOnClickListener {
                val v = McpControlServer.playbackVolume()
                if (v != null) McpControlServer.playbackSetVolume((v - 0.1f).coerceIn(0f, 1f))
                refresh()
            }
            setOnLongClickListener {
                val v = McpControlServer.playbackVolume()
                if (v != null) McpControlServer.playbackSetVolume((v + 0.1f).coerceIn(0f, 1f))
                refresh()
                true
            }
        }
        root.addView(volumeBtn)

        // ===== 安全配置 =====
        root.addView(sectionTitle(getString(R.string.mcp_sec_security)))
        securityTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }.also { root.addView(it) }

        // 可编辑的安全开关（改动经「应用并生效」写入 mcp_security 并热重启监听）
        lanCb = CheckBox(this).apply {
            text = getString(R.string.mcp_lan_cb)
            textSize = 14f
        }.also { root.addView(it) }
        loopbackCb = CheckBox(this).apply {
            text = getString(R.string.mcp_loopback_cb)
            textSize = 14f
        }.also { root.addView(it) }
        originCb = CheckBox(this).apply {
            text = getString(R.string.mcp_origin_cb)
            textSize = 14f
        }.also { root.addView(it) }
        applySecBtn = Button(this).apply {
            text = getString(R.string.mcp_apply_security)
            setOnClickListener {
                McpControlServer.updateSecurity(mapOf(
                    "lan_enabled" to (lanCb?.isChecked == true),
                    "restrict_to_loopback" to (loopbackCb?.isChecked == true),
                    "check_origin" to (originCb?.isChecked == true),
                ))
                toast(getString(R.string.mcp_security_applied))
                refresh()
            }
        }.also { root.addView(it) }

        // ===== 日志 =====
        root.addView(sectionTitle(getString(R.string.mcp_sec_log)))
        logTv = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
        }.also { root.addView(it) }

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun refresh() {
        val status = McpControlServer.status()
        val cfg = McpControlServer.configSnapshot()
        statusTv?.text = buildString {
            append(getString(R.string.mcp_status_service)); append(cfg?.title ?: "-")
            append(getString(R.string.mcp_status_port)); append(cfg?.port ?: "-")
            append(getString(R.string.mcp_status_running_label)); append(if (status.optBoolean("running")) getString(R.string.mcp_running) else getString(R.string.mcp_stopped))
            append(getString(R.string.mcp_status_listeners)); append(status.optInt("listeners"))
            append(getString(R.string.mcp_status_bridge_label)); append(if (status.optBoolean("connected")) getString(R.string.mcp_connected) else getString(R.string.mcp_not_connected))
            append(getString(R.string.mcp_status_auth_label)); append(if (status.optBoolean("tokenSet")) getString(R.string.mcp_enabled) else getString(R.string.mcp_disabled))
        }
        startBtn?.isEnabled = !status.optBoolean("running")
        stopBtn?.isEnabled = status.optBoolean("running")
        val snap = McpControlServer.playbackSnapshot()
        playbackTv?.text = if (snap == null) {
            getString(R.string.mcp_bridge_none)
        } else {
            val phase = if (snap.playing) getString(R.string.mcp_playing) else getString(R.string.mcp_paused)
            buildString {
                append(getString(R.string.mcp_playback_state)); append(phase)
                append(getString(R.string.mcp_queue_label)); append(snap.currentIndex + 1); append("/"); append(snap.queueSize)
                append(getString(R.string.mcp_mode_label)); append(if (snap.shuffle) getString(R.string.mcp_shuffle) else getString(R.string.mcp_sequential)); append("/"); append(snap.repeatMode)
                append(getString(R.string.mcp_track_label)); append(snap.currentTitle ?: "-")
                if (!snap.currentArtist.isNullOrBlank()) { append(getString(R.string.mcp_artist_sep)); append(snap.currentArtist) }
                if (snap.durationMs > 0) {
                    append(getString(R.string.mcp_progress_label)); append(fmt(snap.positionMs)); append(" / "); append(fmt(snap.durationMs))
                }
            }
        }
        val vl = McpControlServer.playbackVolume()
        volumeBtn?.text = "${getString(R.string.mcp_volume_prefix)}${if (vl != null) "${((vl * 100).toInt())}%" else getString(R.string.mcp_volume_unavailable)}${getString(R.string.mcp_volume_hint)}"
        securityTv?.text = McpControlServer.securitySnapshot(this)
            .joinToString("\n") { (k, v) -> "$k: $v" }
        // 把当前生效值回填到可编辑开关
        val sec = McpControlServer.securitySnapshot(this).toMap()
        lanCb?.isChecked = sec[getString(R.string.mcp_sec_lan)] == getString(R.string.mcp_sec_on)
        loopbackCb?.isChecked = sec[getString(R.string.mcp_sec_loopback)] == getString(R.string.mcp_sec_loopback_only)
        originCb?.isChecked = sec[getString(R.string.mcp_sec_origin_check)] == getString(R.string.mcp_sec_on)
        logTv?.text = McpControlServer.logSnapshot(30).joinToString("\n").ifEmpty { getString(R.string.mcp_no_log) }
    }

    private fun toast(msg: String) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    /** 毫秒 → "m:ss" 时长格式。 */
    private fun fmt(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "$m:${if (s < 10) "0" else ""}$s"
    }
}