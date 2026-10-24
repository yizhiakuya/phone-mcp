package top.rainaki.phonemcp.tree

import top.rainaki.phonemcp.action.NodeRef
import java.util.concurrent.atomic.AtomicLong

/**
 * 最近一次 tree 快照的节点指纹表。
 *
 * 保留最近 N 个版本：模型可能先 dump 再连续点几个节点，
 * 中间任何一次 UI 变化都不该让之前的 id 全部失效。
 */
object SnapshotStore {

    private const val MAX_VERSIONS = 4

    private val version = AtomicLong(0)
    private val versions = LinkedHashMap<Long, Map<String, NodeRef>>()

    @Synchronized
    fun record(refs: Map<String, NodeRef>): Long {
        val v = version.incrementAndGet()
        versions[v] = refs
        while (versions.size > MAX_VERSIONS) {
            val oldest = versions.keys.first()
            versions.remove(oldest)
        }
        return v
    }

    @Synchronized
    fun get(id: String): NodeRef? {
        // 从新到旧找
        for (v in versions.keys.toList().asReversed()) {
            versions[v]?.get(id)?.let { return it }
        }
        return null
    }

    @Synchronized
    fun latestVersion(): Long = version.get()

    @Synchronized
    fun clear() = versions.clear()
}
