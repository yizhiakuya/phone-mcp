package top.rainaki.phonemcp.action

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import top.rainaki.phonemcp.tree.TreeSerializer

/**
 * 节点定位。优先级：快照 id → 文本/描述/viewId 精确匹配 → 文本包含匹配。
 *
 * 快照 id 只在同一次 tree 返回内有效；模型拿旧 id 调用时，这里会退化为
 * 用当时记录的文本/描述重新查找，避免一次 UI 刷新就让整轮操作失效。
 */
object NodeLocator {

    class Match(val node: AccessibilityNodeInfo, val bounds: Rect)

    /** 在整棵树上做 DFS 查找。 */
    fun search(
        root: AccessibilityNodeInfo,
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        className: String? = null,
        requireClickable: Boolean = false,
        exact: Boolean = true,
    ): Match? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED) {
            val node = queue.removeFirst()
            visited++
            if (matches(node, text, desc, viewId, className, requireClickable, exact)) {
                return Match(node, boundsOf(node))
            }
            // childCount 是 Binder 调用，遍历时只取一次
            val n = node.childCount
            for (i in 0 until n) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 收集所有匹配项，供"文本有多个候选"时返回列表让模型挑。 */
    fun searchAll(
        root: AccessibilityNodeInfo,
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        requireClickable: Boolean = false,
        limit: Int = 20,
    ): List<Match> {
        val result = ArrayList<Match>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED && result.size < limit) {
            val node = queue.removeFirst()
            visited++
            if (matches(node, text, desc, viewId, null, requireClickable, exact = true)) {
                result.add(Match(node, boundsOf(node)))
            }
            // childCount 是 Binder 调用，遍历时只取一次
            val n = node.childCount
            for (i in 0 until n) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun matches(
        node: AccessibilityNodeInfo,
        text: String?,
        desc: String?,
        viewId: String?,
        className: String?,
        requireClickable: Boolean,
        exact: Boolean,
    ): Boolean {
        if (requireClickable && !node.isClickable) return false
        if (className != null && node.className?.toString()?.substringAfterLast('.') != className) return false
        if (text == null && desc == null && viewId == null) return false

        if (viewId != null) {
            val actual = node.viewIdResourceName ?: return false
            if (actual != viewId && !actual.endsWith("/$viewId")) return false
        }

        // 文本与描述互相兜底。
        //
        // 图标类控件（桌面图标、工具栏按钮）通常**只有 contentDescription 没有 text**；
        // 而正文类控件反过来。调用方分不清某个字符串属于哪个字段，所以：
        //   * 只给了 text → text 或 desc 任一命中即可
        //   * 只给了 desc → desc 或 text 任一命中即可
        //   * 两个都给了 → 两个都要命中（调用方明确要求）
        val textOk = text == null || matchesField(node.text?.toString(), text, exact)
        val descOk = desc == null || matchesField(node.contentDescription?.toString(), desc, exact)
        val crossOk = when {
            text != null && desc == null -> matchesField(node.contentDescription?.toString(), text, exact)
            desc != null && text == null -> matchesField(node.text?.toString(), desc, exact)
            else -> false
        }
        return (textOk && descOk) || crossOk
    }

    private fun matchesField(actual: String?, expected: String, exact: Boolean): Boolean {
        if (actual == null) return false
        return compare(actual, expected, exact)
    }

    private fun compare(actual: String, expected: String, exact: Boolean): Boolean =
        if (exact) actual == expected else actual.contains(expected, ignoreCase = true)

    fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    private const val MAX_VISITED = 4000
}
