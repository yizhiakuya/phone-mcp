package top.rainaki.phonemcp.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import top.rainaki.phonemcp.action.NodeRef

/**
 * 把无障碍树序列化成给模型看的紧凑 JSON，同时产出 id → NodeRef 指纹表。
 *
 * 字段刻意用短键，节点量大时省 token：
 *   i = 节点 id（本次快照内唯一，动作接口用它定位）
 *   c = 类名简写  t = text  d = contentDescription  v = viewIdResourceName
 *   b = 屏幕坐标 [l,t,r,b]  f = 能力标记数组  p = 包名（仅跨应用节点才带）
 */
class TreeSerializer(
    private val maxDepth: Int,
    private val includeInvisible: Boolean,
    /** 节点数上限：超大页面（长列表/WebView）遍历会长时间占用主线程，触发 ANR。 */
    private val maxNodes: Int = 1500,
) {
    /** 是否因节点上限而截断。 */
    var truncated: Boolean = false
        private set


    private val refs = LinkedHashMap<String, NodeRef>()
    private val root = JSONObject()

    fun serialize(
        rootNode: AccessibilityNodeInfo,
        screen: Rect,
        packageName: String?,
        windowTitle: String?,
    ): Snapshot {
        val tree = JSONObject()
        val count = append(rootNode, tree, 0, packageName, isRoot = true)
        root.put("screen", JSONObject().apply {
            put("w", screen.width())
            put("h", screen.height())
        })
        root.put("package", packageName ?: "")
        if (!windowTitle.isNullOrBlank()) root.put("window", windowTitle)
        root.put("node_count", count)
        root.put("root", tree)
        return Snapshot(root, refs, truncated)
    }

    private fun append(
        node: AccessibilityNodeInfo,
        out: JSONObject,
        depth: Int,
        currentPackage: String?,
        isRoot: Boolean,
    ): Int {
        if (refs.size >= maxNodes) {
            truncated = true
            return 0
        }
        val visible = node.isVisibleToUser
        if (!includeInvisible && !visible && !isRoot) return 0

        val id = "n${refs.size}"
        val text = if (node.isPassword) null else node.text?.toString()?.takeIf { it.isNotEmpty() }
        val desc = if (node.isPassword) null else node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
        val viewId = node.viewIdResourceName?.takeIf { it.isNotEmpty() }
        val cls = node.className?.toString().orEmpty()
        val simple = cls.substringAfterLast('.')
        val rect = Rect().also { node.getBoundsInScreen(it) }

        refs[id] = NodeRef(
            id = id,
            text = text,
            desc = desc,
            viewId = viewId,
            className = simple.ifEmpty { null },
            bounds = rect,
        )

        out.put("i", id)
        if (simple.isNotEmpty()) out.put("c", simple)
        text?.let { out.put("t", it) }
        desc?.let { out.put("d", it) }
        viewId?.let { out.put("v", it) }
        if (!rect.isEmpty) out.put("b", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))

        val flags = JSONArray()
        if (node.isClickable) flags.put("click")
        if (node.isLongClickable) flags.put("longclick")
        if (node.isEditable) flags.put("edit")
        if (node.isScrollable) flags.put("scroll")
        if (node.isCheckable) flags.put("checkable")
        if (node.isCheckable && node.isChecked) flags.put("checked")
        if (node.isSelected) flags.put("selected")
        if (node.isFocused) flags.put("focused")
        if (!node.isEnabled) flags.put("disabled")
        if (node.isPassword) flags.put("password")
        if (flags.length() > 0) out.put("f", flags)

        // packageName 是跨进程查询，只取一次
        val pkg = node.packageName?.toString()
        if (pkg != null && pkg != currentPackage) {
            out.put("p", pkg)
        }

        if (depth >= maxDepth) {
            val hidden = node.childCount
            if (hidden > 0) out.put("more", hidden)
            return 1
        }

        var total = 1
        val children = JSONArray()
        // childCount 每次读取都是 Binder 调用，先取一次
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val childJson = JSONObject()
            val n = append(child, childJson, depth + 1, currentPackage, isRoot = false)
            if (n > 0) {
                children.put(childJson)
                total += n
            }
        }
        if (children.length() > 0) out.put("ch", children)
        return total
    }

    class Snapshot(
        val json: JSONObject,
        val refs: Map<String, NodeRef>,
        val truncated: Boolean,
    )
}
