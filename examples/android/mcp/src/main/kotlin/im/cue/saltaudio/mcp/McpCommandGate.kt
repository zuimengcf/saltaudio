package im.cue.saltaudio.mcp

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 播放命令互斥栅栏（下放自盐音 app 层 McpBridge 的 commandLock + 预算范式）。
 *
 * 问题：MCP 是并发的（多连接/多请求），而播放器操作必须串行——两个并发
 * 播放操作会互相踩踏（例如同时 setMediaItems 与 addMediaItems）。
 *
 * 方案：所有带副作用的播放工具在执行前先 acquire()，拿不到锁说明有另一个
 * 播放操作仍在进行，直接拒绝并提示"稍后重试"，而不是排队堆积。
 */
class McpCommandGate(private val budgetMs: Long = 3_000L) {

    private val lock = ReentrantLock()

    /** 尝试在预算时间内获取播放命令锁；返回 null 表示被占用。 */
    fun <T> runOrBusy(block: () -> T): CommandResult<T> {
        val acquired = try {
            lock.tryLock(budgetMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return CommandResult.Busy("interrupted")
        }
        if (!acquired) return CommandResult.Busy("another playback operation in progress")
        return try {
            CommandResult.Ok(block())
        } finally {
            lock.unlock()
        }
    }

    sealed class CommandResult<out T> {
        data class Ok<T>(val value: T) : CommandResult<T>()
        data class Busy(val reason: String) : CommandResult<Nothing>()
    }
}