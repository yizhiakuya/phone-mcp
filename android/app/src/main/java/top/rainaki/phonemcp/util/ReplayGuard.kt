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
 *    等 rid 重新变大后再放"。
 *
 * ## rid 跨进程单调，所以 maxRid 不能归零
 *
 * 电脑端用「毫秒时间戳 * 1000 + 进程内序号」构造 rid，天然单调且跨进程安全：
 * MCP 进程重启后时间戳更大，新请求的 rid 一定大于旧值。
 *
 * 因此手机端**必须保留** maxRid——[reset] 只在配对/清除配对时调用。
 * 如果在每次重连时归零，就放开了"中继断开重连后重放旧密文"的攻击窗口。
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

    /**
     * 重置计数。**必须在每次隧道建立时调用**。
     *
     * 原因见类注释：rid 是电脑端 MCP 进程的局部计数器，
     * 电脑端重启后从 1 重新开始，手机端必须跟着归零。
     */
    @Synchronized
    fun reset() {
        maxRid = 0
    }
}
