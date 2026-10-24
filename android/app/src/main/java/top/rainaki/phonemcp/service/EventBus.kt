package top.rainaki.phonemcp.service

import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * 无障碍事件缓冲 + 阻塞等待。
 *
 * ## 为什么需要
 *
 * 没有它，模型只能「问一句答一句」：想知道有没有新消息就得反复 `phone_observe`
 * 轮询——慢、烧 token，还经常在两个采样点之间错过。
 *
 * 有了它，模型可以「挂起等待」：手机端收到匹配事件时立刻返回。交互从轮询
 * 变成监听。这是 Android 无障碍服务独有的能力，iOS 没有对应接口。
 *
 * ## 设计
 *
 * 环形缓冲保留最近 [MAX_ENTRIES] 条，带单调递增 id。等待时记录当前 id，
 * 之后只关心更新的条目——避免重复返回同一条。
 *
 * 等待用 `ReentrantLock` + `Condition`，不是忙等：没有事件时线程真正挂起，
 * 不占 CPU。
 */
object EventBus {

    /** 一条事件。字段刻意精简——它要经过中继、进模型的上下文。 */
    data class Entry(
        val id: Long,
        val type: String,
        val pkg: String?,
        val text: String?,
        val at: Long,
    )

    /** 环形缓冲容量。够覆盖一次典型等待，又不至于占内存。 */
    private const val MAX_ENTRIES = 120

    /** 单条文本最大长度，防止超长消息把缓冲撑爆。 */
    private const val MAX_TEXT_LEN = 160

    private val seq = AtomicLong(0)
    private val buffer = ArrayDeque<Entry>()
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val signal = lock.newCondition()

    /** 当前最新事件 id。等待前记下它，之后只看更新的。 */
    fun currentId(): Long = seq.get()

    fun record(type: String, pkg: String?, text: String?) {
        val entry = Entry(
            id = seq.incrementAndGet(),
            type = type,
            pkg = pkg,
            text = text?.replace('\n', ' ')?.take(MAX_TEXT_LEN),
            at = System.currentTimeMillis(),
        )
        lock.lock()
        try {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            signal.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** 取 id 大于 [afterId] 的事件。 */
    fun since(afterId: Long, limit: Int = 50): List<Entry> {
        lock.lock()
        try {
            return buffer.filter { it.id > afterId }.takeLast(limit)
        } finally {
            lock.unlock()
        }
    }

    /**
     * 阻塞等待一条匹配的事件。
     *
     * @param afterId 只看 id 大于它的事件
     * @param pkg 包名精确匹配（null = 不限）
     * @param textContains 文本/描述包含该子串（null = 不限）
     * @param types 事件类型白名单（空 = 不限）
     * @return 命中的事件；超时返回 null
     */
    fun await(
        afterId: Long,
        pkg: String? = null,
        textContains: String? = null,
        types: Set<String> = emptySet(),
        timeoutMs: Long,
    ): Entry? {
        val deadline = System.currentTimeMillis() + timeoutMs
        lock.lock()
        try {
            var cursor = afterId
            while (true) {
                // 先看已有缓冲——事件可能在上一次等待返回后已经到过
                val hit = buffer.firstOrNull { it.id > cursor && matches(it, pkg, textContains, types) }
                if (hit != null) return hit
                buffer.lastOrNull()?.let { cursor = maxOf(cursor, it.id) }

                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                signal.await(left, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            lock.unlock()
        }
    }

    private fun matches(
        e: Entry,
        pkg: String?,
        textContains: String?,
        types: Set<String>,
    ): Boolean {
        if (types.isNotEmpty() && e.type !in types) return false
        if (pkg != null && e.pkg != pkg) return false
        if (textContains != null) {
            val hay = e.text ?: return false
            if (!hay.contains(textContains)) return false
        }
        return true
    }

    /** 把 AccessibilityEvent 转成缓冲条目。 */
    fun recordFrom(event: AccessibilityEvent) {
        val type = typeName(event.eventType) ?: return
        val text = runCatching {
            event.text?.joinToString(" ")?.trim()?.ifBlank { null }
                ?: event.contentDescription?.toString()
        }.getOrNull()
        record(type, event.packageName?.toString(), text)
    }

    private fun typeName(type: Int): String? = when (type) {
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "notification"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "click"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "long_click"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "focus"
        else -> null
    }

    /** 事件类型名 → 中文说明，给工具层做错误提示用。 */
    val knownTypes: List<String> = listOf(
        "notification", "window", "content", "click", "long_click", "scroll", "text", "focus",
    )

    fun clear() {
        lock.lock()
        try {
            buffer.clear()
        } finally {
            lock.unlock()
        }
    }
}
