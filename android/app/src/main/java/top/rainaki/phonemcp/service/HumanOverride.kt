package top.rainaki.phonemcp.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 人工中断。
 *
 * 只有一种触发方式：**用户点屏幕底部的状态条**。
 *
 * 为什么不用自动检测触摸：Android 无法区分 `dispatchGesture` 注入的手势与
 * 真人触摸——系统层面两者完全一致，没有任何 flag 或标记可查。任何自动判定
 * 都只能在"误判自己的动作为用户操作"（AI 完全没法用）和"漏掉用户真实操作"
 * （抢不到）之间摇摆。让用户主动点一下，语义明确、零误判。
 *
 * ## 中断是锁，不是冷却
 *
 * 早期设计是「中断后 10 秒自动放行」。这个设计的致命缺陷：被注入的模型
 * 只要等 10 秒重试就能继续操作，中断形同虚设——而中断恰恰是用户夺回控制权
 * 的最后手段。
 *
 * 现在改为**保持锁定，直到用户手动恢复**：
 *   * 用户点「停止」→ 锁定，所有动作被拒
 *   * 用户点「恢复」→ 解锁
 *   * 锁定期间 AI 的任何重试都会被拒，不存在"等一会儿就自动放行"
 *
 * 恢复入口只在手机本地（状态条按钮 + App 首页），远程接口没有任何解锁能力。
 */
object HumanOverride {

    private val stopped = AtomicBoolean(false)
    private val stoppedAt = AtomicLong(0)
    private val blockedCount = AtomicLong(0)

    /** 动作是否正在执行（中断条据此显示）。 */
    @Volatile
    var actionRunning: Boolean = false
        private set

    fun blockedCount(): Long = blockedCount.get()

    /** 用户点了中断。锁定，直到 [resume] 被调用。 */
    fun requestStop() {
        stopped.set(true)
        stoppedAt.set(System.currentTimeMillis())
        android.util.Log.i("HumanOverride", "user requested stop (locked until resume)")
    }

    /**
     * 用户手动恢复。
     *
     * **只能由手机本地 UI 调用**——远程接口不暴露解锁能力，
     * 否则模型可以自己解锁，中断就白做了。
     */
    fun resume() {
        stopped.set(false)
        stoppedAt.set(0)
        android.util.Log.i("HumanOverride", "user resumed control")
    }

    /** 是否处于锁定状态。 */
    fun isStopped(): Boolean = stopped.get()

    /** 已锁定多久（毫秒）。未锁定时为 0。 */
    fun lockedForMs(now: Long = System.currentTimeMillis()): Long {
        if (!stopped.get()) return 0
        return (now - stoppedAt.get()).coerceAtLeast(0)
    }

    /**
     * 动作前检查。处于锁定状态时抛异常，由上层转成明确的停止响应。
     */
    fun check() {
        if (stopped.get()) {
            blockedCount.incrementAndGet()
            throw UserOperatingException(lockedForMs())
        }
    }

    fun beginAction() {
        actionRunning = true
    }

    fun endAction() {
        actionRunning = false
    }
}

/**
 * 用户中断了自动操作。
 *
 * @param lockedMs 已锁定时长。不再有"多久后放行"的概念——必须用户手动恢复。
 */
class UserOperatingException(val lockedMs: Long) :
    IllegalStateException("用户已中断自动操作，需在手机上手动恢复")
