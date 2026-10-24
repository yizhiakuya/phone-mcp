package top.rainaki.phonemcp.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 人工中断。
 *
 * 只有一种触发方式：**用户点屏幕顶部的「中断」条**。
 *
 * 为什么不用自动检测触摸：Android 无法区分 `dispatchGesture` 注入的手势与
 * 真人触摸——系统层面两者完全一致，没有任何 flag 或标记可查。任何自动判定
 * 都只能在"误判自己的动作为用户操作"（AI 完全没法用）和"漏掉用户真实操作"
 * （抢不到）之间摇摆。让用户主动点一下，语义明确、零误判。
 *
 * 中断后的行为：
 *   * 当前正在执行的动作立即失败返回
 *   * 后续动作在 [STOP_HOLD_MS] 内继续被拒绝，避免 AI 立刻又动起来
 *   * 不需要"恢复"操作：过了保持期自然放行，AI 重新尝试即可
 */
object HumanOverride {

    /** 中断后保持拒绝的时长。足够让 AI 停手并把控制权交还用户。 */
    const val STOP_HOLD_MS = 10_000L

    private val stopped = AtomicBoolean(false)
    private val stoppedAt = AtomicLong(0)
    private val blockedCount = AtomicLong(0)

    /** 动作是否正在执行（中断条据此显示）。 */
    @Volatile
    var actionRunning: Boolean = false
        private set

    fun blockedCount(): Long = blockedCount.get()

    /** 用户点了中断。 */
    fun requestStop() {
        stopped.set(true)
        stoppedAt.set(System.currentTimeMillis())
        android.util.Log.i("HumanOverride", "user requested stop")
    }

    /** 是否处于中断状态（且未过保持期）。 */
    fun isStopped(now: Long = System.currentTimeMillis()): Boolean {
        if (!stopped.get()) return false
        if (now - stoppedAt.get() < STOP_HOLD_MS) return true
        stopped.set(false)
        return false
    }

    /** 距离解除中断还剩多少毫秒。0 = 当前未中断。 */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        if (!stopped.get()) return 0
        val left = STOP_HOLD_MS - (now - stoppedAt.get())
        return left.coerceAtLeast(0)
    }

    /**
     * 动作前检查。处于中断状态时抛异常，由上层转成明确的停止响应。
     */
    fun check() {
        if (isStopped()) {
            blockedCount.incrementAndGet()
            throw UserOperatingException(remainingMs())
        }
    }

    fun beginAction() {
        actionRunning = true
    }

    fun endAction() {
        actionRunning = false
    }
}

/** 用户中断了自动操作。上层据此返回 409 并停止本轮任务。 */
class UserOperatingException(val retryAfterMs: Long) :
    IllegalStateException("用户已中断自动操作")
