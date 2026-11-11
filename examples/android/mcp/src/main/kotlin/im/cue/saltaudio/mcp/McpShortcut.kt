package im.cue.saltaudio.mcp

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * 桌面快捷方式：长按 app 图标 → 一键进入 MCP 控制面板。
 *
 * 用途：即使 MCP 停止 / 常驻通知被移除，也保留一个不被消失的入口，
 * 避免「停止后找不到设置入口」的困境（通知可被删、app 可退后台，快捷方式常驻桌面）。
 *
 * 用 [ShortcutManagerCompat]：API 26+ 注册为动态快捷方式，API 24/25 由兼容层自动退化为 pin 快捷方式。
 */
object McpShortcut {

    const val ID = "mcp_settings"

    /** 注册（或更新）「MCP 控制面板」桌面快捷方式。重复调用会覆盖同名 ID，幂等。 */
    fun register(context: Context, title: String, iconRes: Int = android.R.drawable.ic_menu_manage) {
        runCatching {
            val ctx = context.applicationContext
            val intent = Intent(ctx, McpSettingsActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val shortcut = ShortcutInfoCompat.Builder(ctx, ID)
                .setShortLabel(title)
                .setLongLabel(context.getString(R.string.mcp_shortcut_long))
                .setIcon(IconCompat.createWithResource(ctx, iconRes))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.addDynamicShortcuts(ctx, listOf(shortcut))
            McpLog.log("desktop shortcut registered: $title")
        }.onFailure { McpLog.log("desktop shortcut register failed: ${it.message}") }
    }
}