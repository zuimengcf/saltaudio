package im.cue.saltaudio.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/**
 * MCP 常驻状态通知：无宿主 UI 场景下接入 MCP 的唯一"操作台"。
 *
 * 设计（延续防回环）：
 * - 通知栏**不显示** token 明文，也没有其它旁路（logcat / HTTP 工具都拿不到明文）。
 * - token 只能通过「复制」按钮，点击后才写剪贴板（防旁人/截屏窥视）。
 * - 重置：点「重置」立即换新钥并刷新通知。
 * - 设置：点「设置」拉起 App 注册的 [McpSettingsActivity] 正式设置页（复制/重置 token、播放控制等）。
 * - token 绝不通过 MCP HTTP 工具/接口返回（避免从自身守护的通道送出密钥）。
 */
object McpStatusNotifier {

    const val CHANNEL_ID = "mcp_control_status"
    const val NOTIFICATION_ID = 18790
    const val ACTION_COPY_TOKEN = "im.cue.saltaudio.mcp.ACTION_COPY_TOKEN"
    const val ACTION_RESET_TOKEN = "im.cue.saltaudio.mcp.ACTION_RESET_TOKEN"

    /** 通知动作接收器：处理 复制 / 重置。token 在 Receiver 里从 server 实时读，不进 intent。 */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_COPY_TOKEN -> {
                    val t = McpControlServer.accessToken()
                    if (t != null) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("mcp_token", t))
                        toast(context, context.getString(R.string.mcp_token_copied))
                        McpLog.log("access token copied to clipboard")
                    } else {
                        toast(context, context.getString(R.string.mcp_no_token))
                    }
                }
                ACTION_RESET_TOKEN -> {
                    McpControlServer.resetAccessToken()
                    McpControlServer.refreshStatusNotification()
                    toast(context, context.getString(R.string.mcp_token_reset))
                    McpLog.log("access token reset")
                }
            }
        }

        private fun toast(context: Context, msg: String) {
            try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    fun ensureChannel(context: Context, channelName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.description = context.getString(R.string.mcp_notif_channel_desc)
        nm.createNotificationChannel(ch)
    }

    private fun buildShow(state: String, context: Context, title: String, icon: Int): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    fun show(context: Context, title: String, running: Boolean, port: Int, listeners: Int, icon: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val state = if (running) context.getString(R.string.mcp_state_running, listeners) else context.getString(R.string.mcp_state_stopped)
        val builder = buildShow(state, context, title, icon)
        builder
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.mcp_content_text, state, port))
            .setStyle(Notification.BigTextStyle().bigText(
                context.getString(R.string.mcp_notif_big, state, port)
            ))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(icon, context.getString(R.string.mcp_action_copy), pending(context, ACTION_COPY_TOKEN))
            .addAction(icon, context.getString(R.string.mcp_action_reset), pending(context, ACTION_RESET_TOKEN))
            .addAction(icon, context.getString(R.string.mcp_action_settings), openSettings(context))
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * 「设置」直接用 PendingIntent.getActivity 启动设置页，
     * 由系统在启动 Activity 时自动收起通知下拉面板（不透传广播，避免面板不收起遮挡页面）。
     */
    private fun openSettings(context: Context): PendingIntent {
        val intent = Intent(context, McpSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(context, 0x5E77, intent, flags)
    }

    fun hide(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID)
    }

    private fun pending(context: Context, action: String): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }
}