package top.rainaki.phonemcp.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import top.rainaki.phonemcp.PhoneMcpApp
import java.util.concurrent.ConcurrentHashMap

/**
 * 资金保护：硬拦截一切可能动钱的操作。
 *
 * ## 为什么放在这一层
 *
 * 模型是不可信的输入源——一句「帮我付一下」或页面里藏的提示注入就能让它照做。
 * 所以不能靠提示词，也不能靠电脑端的参数检查（APK 版本旧了就绕过）。
 * 必须放在**离动作最近、且 AI 没有任何工具能修改**的地方：手机端的无障碍服务。
 *
 * 这里没有任何远程开关。策略是编译期常量，想改只能改代码重新打包。
 * MCP 的 15 个工具里没有一个能读写这份策略。
 *
 * ## 三道闸
 *
 * 1. **整包拒绝**（[BLOCKED_PACKAGES] + [LABEL_KEYWORDS]）
 *    支付宝、银行、券商、云闪付这类 App，前台是它就一律不允许任何动作。
 *    包名来自系统而不是模型，改不了；应用名用中文关键词兜住新装/改名的 App。
 *
 * 2. **目标链拒绝**（[TARGET_KEYWORDS]）
 *    要点的那个节点，以及它往上 8 层父节点，文本/描述/资源 id 命中资金词就拒。
 *    覆盖「确认支付」这类按钮，也覆盖按钮本身没字、父容器有字的布局。
 *
 * 3. **整屏拒绝**（[SCREEN_KEYWORDS]）
 *    当前页面出现「付款码 / 确认支付 / 提交订单 / 支付密码」这类**只会在支付页出现**
 *    的标记时，该页的点击、输入、滑动全部拒绝。
 *    这一层是为微信、淘宝这类「平时要用、里面才有支付」的 App 准备的。
 *
 * ## 已知边界（必须承认）
 *
 * 纯图片/Canvas 渲染的支付页、以及应用名和页面文字都不像资金的场景，三道闸都命中不了。
 * 这不是可以靠加词表解决的——没有可读文本就没有判据。
 * 真正的兜底是：**不要在这台手机上登录支付类账号**。
 */
object MoneyGuard {

    /** 资金操作被拦截。上层转成 403 + money_blocked，服务端抛 ToolError。 */
    class BlockedException(reason: String) : IllegalStateException(reason)

    // ------------------------------------------------------------ 策略

    /**
     * 整包拒绝。前台是这个包，任何动作（点击/输入/滑动/按键/启动）都不执行。
     *
     * 只列「除了动钱没别的用途」的 App。微信/淘宝/京东这类日常要用的不在此列，
     * 它们由第 2、3 道闸覆盖。
     */
    private val BLOCKED_PACKAGES = setOf(
        // 支付
        "com.eg.android.AlipayGphone",          // 支付宝
        "com.unionpay",                          // 云闪付
        "cn.gov.pbc.dcep",                       // 数字人民币
        "com.jd.jrapp",                          // 京东金融
        "com.antfortune.wealth",                 // 蚂蚁财富
        "com.taobao.mobile.deposit",             // 淘宝资金
        "com.alipay.mobile.wealth",              // 支付宝理财
        // 银行
        "com.icbc",                              // 工商银行
        "com.chinamworld.main",                  // 建设银行
        "com.chinamworld.bocmbci",               // 中国银行
        "com.android.bankabc",                   // 农业银行
        "com.bankcomm.Bankcomm",                 // 交通银行
        "com.cmbchina.ccd.pluto.cmbActivity",    // 招商银行
        "com.spdbccc.app",                       // 浦发银行
        "com.cgbchina.xpt",                      // 广发银行
        "com.ecitic.bank.mobile",                // 中信银行
        "com.pingan.paces.ccms",                 // 平安口袋银行
        "com.cebbank.mobile.cemb",               // 光大银行
        "com.yitong.mbank.psbc",                 // 邮储银行
        "com.cmbchina.creditcard",               // 掌上生活
        // 券商 / 基金
        "com.hexin.plat.android",                // 同花顺
        "com.eastmoney.android.berlin",          // 东方财富
        "com.htsc.mdc",                          // 涨乐财富通
        "com.guosen.android",                    // 国信金太阳
        "com.lphtsccft",                         // 平安证券
        // 系统钱包
        "com.miui.wallet",                       // 小米钱包
        "com.huawei.wallet",                     // 华为钱包
        "com.oplus.wallet",                      // OPPO 钱包
        "com.vivo.wallet",                       // vivo 钱包
        "com.samsung.android.spay",              // 三星 Pay
        "com.google.android.apps.walletnfcrel",  // Google Wallet
        "com.paypal.android.p2pmobile",          // PayPal
        // 加密资产
        "com.binance.dev",                       // Binance
        "com.okinc.okex.gp",                     // OKX
        "com.coinbase.android",                  // Coinbase
    )

    /**
     * 应用名关键词。包名列表永远追不上新 App，用中文名兜一层。
     * 命中的是「前台 App 的显示名」，比如「招商银行」「同花顺」「数字人民币」。
     */
    private val LABEL_KEYWORDS = listOf(
        "银行", "支付", "钱包", "证券", "股票", "期货", "基金", "理财",
        "保险", "信用卡", "花呗", "借呗", "白条", "数字人民币", "云闪付",
        "借贷", "贷款", "分期", "征信", "信托", "邮储", "农信", "农商",
        "村镇银行", "外汇", "黄金", "贵金属", "财富", "资产管理",
        "bank", "pay", "wallet", "alipay", "unionpay", "paypal",
        "broker", "trading", "crypto", "binance", "coinbase", "finance",
    )

    /**
     * 目标链关键词。要点的节点或它的父容器命中即拒。
     *
     * 刻意收得比整屏词表宽——点错了代价大，误拒只是多一次失败。
     */
    private val TARGET_KEYWORDS = listOf(
        "支付", "付款", "转账", "汇款", "打款", "收款", "提现", "充值",
        "购买", "下单", "结算", "还款", "缴费", "红包", "打赏", "免密",
        "支付密码", "验证码", "银行卡", "绑卡", "钱包", "余额", "花呗",
        "借呗", "白条", "分期", "续费", "订阅", "开通会员", "确认收货",
        "立即购买", "提交订单", "确认支付", "去支付", "立即支付",
        "扫码支付", "收款码", "付款码", "金额", "扣款", "退款",
    )

    /**
     * 整屏关键词。**只收那些基本只会在真实支付页出现的词**。
     *
     * 刻意排除了「提现」「充值」「支付」「转账」这类泛词——它们大量出现在
     * 广告横幅、聊天消息、公众号标题里（微信首页就挂着「微信支付提现笔笔省」
     * 的广告），收进来会把整个 App 锁死。
     *
     * 留下的都是**支付页独有的动作/字段名**：看到「确认支付」说明真的在支付页上。
     */
    private val SCREEN_KEYWORDS = listOf(
        "付款码", "二维码收款", "确认支付", "立即支付", "去支付",
        "输入支付密码", "指纹支付", "面容支付", "免密支付",
        "转账金额", "确认转账", "收款方", "付款方式", "付款账户", "收款账户",
        "提交订单", "去结算", "确认下单", "订单金额", "实付款", "应付金额",
        "信用卡还款", "钱包余额", "话费充值", "游戏充值", "到账金额", "确认付款",
    )

    /** 中断/退出类按键永远放行——否则在支付页连退出去都做不到。 */
    private val ALWAYS_ALLOWED_KEYS = setOf("back", "home")

    /**
     * 只读浏览类动作：在资金页上也放行。
     *
     * 滚动不改钱，但被拦掉的话用户在「服务」页连找「城市服务」都做不到——
     * 整个 App 变成只读。真正危险的是**点击**（会触发支付流程）。
     * 滑动同理：`phone_swipe` 常用于翻页，但也能用来画支付密码的九宫格，
     * 所以只放行 scroll（明确的滚动语义），不放行裸 swipe。
     */
    private val READ_ONLY_PATHS = setOf("scroll")

    /**
     * 资源 id 里的英文词。按 `_`/`-`/`.`/驼峰切词后**整词**比对，
     * 避免 "pay" 命中 "display" 这类误伤。
     */
    private val MONEY_ID_TOKENS = setOf(
        "pay", "payment", "paypal", "transfer", "remit", "recharge", "withdraw",
        "checkout", "settle", "settlement", "buy", "purchase", "topup",
        "wallet", "balance", "bankcard", "creditcard",
    )

    /** 整屏扫描的节点上限，防止超大页面拖慢主线程。 */
    private const val SCREEN_SCAN_LIMIT = 400

    /** 目标链向上追溯的层数。 */
    private const val CHAIN_DEPTH = 8

    /**
     * 整屏扫描只认这个长度以内的文本。
     *
     * 支付页上的标记都是短标签（「确认支付」「付款码」）；长文本多半是
     * 聊天记录、文章正文、商品详情，里面提一句「转账」不该把整个 App 锁死。
     */
    private const val SCREEN_TEXT_MAX_LEN = 24

    private val labelCache = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------ 入口

    /**
     * 第一段检查：整包 + 整屏。**必须在主线程调用**。
     *
     * 在动作开始前调用（此时还不知道要操作哪个节点）。覆盖坐标手势、
     * 按键、启动 App 这类没有目标节点的动作。
     *
     * @param keyAction 按键名；back/home 直接放行
     * @param readOnly 只读浏览类动作（如滚动）；跳过整屏判定，只查包名
     */
    fun enforceBasic(
        svc: PhoneMcpService,
        keyAction: String? = null,
        readOnly: Boolean = false,
    ) {
        if (keyAction != null && keyAction.lowercase() in ALWAYS_ALLOWED_KEYS) return

        // 闸 1：整包（只读也拦——资金类 App 界面本身就是敏感数据）
        checkPackage(svc.currentPackage())

        // 闸 3：整屏（只读浏览跳过）
        if (readOnly) return
        val root = svc.activeRoot()
        if (root != null) checkScreen(root)
    }

    /**
     * 第二段检查：目标链。**必须在主线程调用**，且节点已解析出来。
     *
     * 覆盖「点了这个按钮会发生什么」——按钮本身没字但父容器写着「确认支付」也能拦住。
     */
    fun enforceTarget(node: AccessibilityNodeInfo?) {
        if (node != null) checkChain(node)
    }

    /**
     * 第二段检查（OCR 版）：目标只有文字，没有 NodeInfo。
     *
     * OCR 识别的文字同样可能写着「确认支付」——不能因为拿不到节点就跳过判定。
     */
    fun enforceTargetText(text: String?) {
        if (text.isNullOrBlank()) return
        val hit = firstHit(text)
        if (hit != null) {
            throw BlockedException("目标涉及资金（命中「$hit」），已拒绝执行")
        }
    }

    /**
     * 读取类检查：只拦**整包**判定，不拦整屏判定。
     *
     * 资金类 App 的界面本身就是敏感数据：余额、卡号、付款码（不记名凭证，
     * 谁看到谁能付）。AI 没有正当理由去读这些，所以连截图和界面树一并拒掉。
     *
     * 刻意不套用整屏判定：那会让「微信里刚好停在一个提到金额的页面」
     * 连读都读不了，把日常使用堵死。整屏判定只用于动作。
     */
    fun enforceRead(svc: PhoneMcpService) {
        checkPackage(svc.currentPackage())
    }

    /** 启动 App 前的检查。被禁的包连启动都不允许。 */
    fun checkPackage(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (pkg in BLOCKED_PACKAGES) {
            throw BlockedException("「${labelOf(pkg) ?: pkg}」是资金类应用，已禁止远程操作")
        }
        val label = labelOf(pkg) ?: return
        val hit = LABEL_KEYWORDS.firstOrNull { label.contains(it, ignoreCase = true) }
        if (hit != null) {
            throw BlockedException("「$label」被判定为资金类应用（命中「$hit」），已禁止远程操作")
        }
    }

    /** 坐标点击时找该点下最深的节点，用来做目标链判定。 */
    fun nodeAt(root: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        val point = Rect(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1)
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < SCREEN_SCAN_LIMIT) {
            val n = queue.removeFirst()
            visited++
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (rect.contains(point)) {
                val area = rect.width().toLong() * rect.height()
                if (area in 0 until bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    // ------------------------------------------------------------ 内部

    private fun checkChain(node: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth++ < CHAIN_DEPTH) {
            val hit = firstHit(
                cur.text?.toString(),
                cur.contentDescription?.toString(),
                cur.viewIdResourceName,
            )
            if (hit != null) {
                throw BlockedException("目标涉及资金（命中「$hit」），已拒绝执行")
            }
            cur = cur.parent
        }
    }

    private fun checkScreen(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < SCREEN_SCAN_LIMIT) {
            val n = queue.removeFirst()
            visited++
            // 只看用户真正能看到的节点。
            // 微信首页挂着一堆不可见的广告/预加载节点（"微信支付提现笔笔省"），
            // 算进来会把整个 App 误判成支付页。
            if (!n.isVisibleToUser) continue
            // 只认短文本：按钮/标签类。聊天记录里提一句「转账」不该把整个微信锁死。
            val fields = arrayOf(n.text?.toString(), n.contentDescription?.toString())
            for (f in fields) {
                if (f == null || f.isBlank() || f.length > SCREEN_TEXT_MAX_LEN) continue
                val hit = SCREEN_KEYWORDS.firstOrNull { f.contains(it) }
                if (hit != null) {
                    throw BlockedException(
                        "当前页面是资金相关界面（命中「$hit」），已拒绝所有改动操作"
                    )
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    /** 目标链判定：文本/描述用中文词表，资源 id 用整词比对。 */
    private fun firstHit(vararg fields: String?): String? {
        for (f in fields) {
            if (f.isNullOrBlank()) continue
            val hit = TARGET_KEYWORDS.firstOrNull { f.contains(it) }
            if (hit != null) return hit
            val tokens = idTokens(f) intersect MONEY_ID_TOKENS
            if (tokens.isNotEmpty()) return tokens.joinToString(",")
        }
        return null
    }

    private fun idTokens(raw: String): Set<String> {
        val lowered = raw.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
        return lowered.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
    }

    /** 前台包名 → 应用显示名。缓存，避免每次动作都走一次 PackageManager。 */
    private fun labelOf(pkg: String): String? {
        labelCache[pkg]?.let { return it.ifBlank { null } }
        val label = try {
            val pm = PhoneMcpApp.instance.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (t: Throwable) {
            ""
        }
        labelCache[pkg] = label
        return label.ifBlank { null }
    }

    /** 供 /health 暴露策略摘要，方便确认手机端装的是哪一版。 */
    fun policySummary(): org.json.JSONObject = org.json.JSONObject().apply {
        put("blocked_packages", BLOCKED_PACKAGES.size)
        put("label_keywords", LABEL_KEYWORDS.size)
        put("target_keywords", TARGET_KEYWORDS.size)
        put("screen_keywords", SCREEN_KEYWORDS.size)
    }
}
