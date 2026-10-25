package top.rainaki.phonemcp.util

/**
 * 重放防护。
 *
 * 中继是本项目明确设定的**不可信节点**——它能读到每一条密文，虽然解不开，
 * 但可以原样再发一次。只靠 AAD 绑定 device+path 挡不住这种攻击：
 * 重放的密文 AAD 完全匹配，解密会成功，动作会被执行。
 *
 * 这里维护两个判据：
 *
 * 1. **rid 单调递增** —— 记住见过的最大请求号，比它小或相等的直接拒。
 *    电脑端每次请求 rid+1，所以重放的旧请求必然不大于已见最大值。
 *
 * 2. **ts 时间窗口** —— 超出 [WINDOW_SEC] 的拒绝。防的是"中继攒一批密文，
 *    等 rid 重新变大后再放"（本项目 rid 从 0 开始单调增，不会回绕，
 *    所以这一层主要是纵深防御）。
 *
 * ## 为什么要容忍乱序
 *
 * 请求经过中继转发，理论上可能乱序到达。但本项目动作是**串行执行**的
 * （TunnelClient 用单线程 worker），同一时刻只有一个请求在飞，所以
 * 严格单调是安全的。若将来改成并发，这里要换成滑动窗口。
 *
 * ## 为什么不持久化
 *
 * 进程重启后 rid 归零。攻击者可以在重启后重放旧密文——但重启后电脑端也会
 * 从 rid=1 重新开始，重放的旧密文（rid 较大）反而会被当成"未来"的请求。
 * 加上 ts 窗口，超过两分钟的旧密文一律拒。真正要持久化的话收益有限，
 * 复杂度却明显上升。
 */
object ReplayGuard {

    /** 时间戳有效窗口（秒）。与 Python 端 REPLAY_WINDOW_SEC 保持一致。 */
    const val WINDOW_SEC = 120L

    private var maxRid = 0L

    /**
     * 校验并记录。返回 null 表示通过，否则返回拒绝原因。
     *
     * @param rid 请求号
     * @param ts Unix 秒
     * @param now 当前时间（测试注入用）
     */
    @Synchronized
    fun check(rid: Long, ts: Long, now: Long = System.currentTimeMillis() / 1000): String? {
        if (rid <= 0) return "请求缺少请求号"
        if (ts <= 0) return "请求缺少时间戳"
        if (kotlin.math.abs(now - ts) > WINDOW_SEC) return "请求已过期（时间戳偏差过大）"
        if (rid <= maxRid) return "请求号重复（疑似重放）"
        maxRid = rid
        return null
    }

    /** 当前已见的最大请求号，诊断用。 */
    @Synchronized
    fun currentRid(): Long = maxRid

    @Synchronized
    fun reset() {
        maxRid = 0
    }
}
