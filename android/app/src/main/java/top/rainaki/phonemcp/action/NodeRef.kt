package top.rainaki.phonemcp.action

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 快照内节点 id → 可重定位的指纹。
 *
 * 无障碍树每次 dump 都会重新生成 NodeInfo，旧对象不能跨调用复用，
 * 所以这里只留"怎么重新找到它"的最小信息，动作时在最新树上重定位。
 */
data class NodeRef(
    val id: String,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val className: String?,
    val bounds: Rect,
) {
    fun describe(): String = buildString {
        if (!text.isNullOrBlank()) append("text=\"$text\" ")
        if (!desc.isNullOrBlank()) append("desc=\"$desc\" ")
        if (!viewId.isNullOrBlank()) append("id=$viewId ")
        if (!className.isNullOrBlank()) append("class=$className")
    }.trim()
}

/**
 * 在最新树上重定位一个 NodeRef。
 * 先按 viewId+文本找，再按文本找，最后退化为"同一坐标位置上的节点"。
 */
object NodeRelocator {

    fun relocate(root: AccessibilityNodeInfo, ref: NodeRef): AccessibilityNodeInfo? {
        val byFingerprint = NodeLocator.search(
            root = root,
            text = ref.text,
            desc = ref.desc,
            viewId = ref.viewId,
            className = ref.className,
            exact = true,
        )
        if (byFingerprint != null) return byFingerprint.node

        // 文本可能已经变化（如输入框内容），退化为坐标命中
        return nodeAtPoint(root, ref.bounds.centerX(), ref.bounds.centerY())
    }

    /** 深度优先找包含该坐标、且面积最小的可见节点——即最贴合手指落点的控件。 */
    fun nodeAtPoint(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y) && node.isVisibleToUser) {
                val area = rect.width().toLong() * rect.height()
                if (area in 1 until bestArea) {
                    best = node
                    bestArea = area
                }
            }
            val n = node.childCount
            for (i in 0 until n) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }
}
