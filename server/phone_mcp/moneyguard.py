"""资金保护（服务端兜底）。

真正的拦截在手机端 `MoneyGuard.kt`——那里能看到前台包名、目标节点和整屏文字，
是唯一 AI 改不了的地方。这里只做**参数级镜像检查**，用于两种情况：

1. 手机端是旧版 APK（没有 MoneyGuard），至少还能挡住「点『确认支付』」这种直白指令；
2. 手机端被判定的内容不在参数里（例如模型用坐标点击）时，这里查不出问题，
   但会挡住明显的 `text="确认支付"`。

**这不是安全边界**。词表匹配永远有漏网之鱼，参数里也不一定带关键词。
安全边界是手机端的包名/整屏判定，以及"不在这台手机上登录支付账号"。
"""

from __future__ import annotations

from typing import Any

# 与 android/.../service/MoneyGuard.kt 的 TARGET_KEYWORDS 保持同源。
# 两边不一致不致命（手机端更严即可），但新增词要两边都加。
MONEY_KEYWORDS: tuple[str, ...] = (
    "支付", "付款", "转账", "汇款", "打款", "收款", "提现", "充值",
    "购买", "下单", "结算", "还款", "缴费", "红包", "打赏", "免密",
    "支付密码", "验证码", "银行卡", "绑卡", "钱包", "余额", "花呗",
    "借呗", "白条", "分期", "续费", "订阅", "开通会员", "确认收货",
    "立即购买", "提交订单", "确认支付", "去支付", "立即支付",
    "扫码支付", "收款码", "付款码", "金额", "扣款", "退款",
)

# 整屏关键词（与手机端 SCREEN_KEYWORDS 同源，仅用于文档对照）。
# 手机端才是真正的执行者；这里列出来是为了让两边的词表一眼能对齐。
SCREEN_KEYWORDS: tuple[str, ...] = (
    "付款码", "二维码收款", "确认支付", "立即支付", "去支付",
    "输入支付密码", "指纹支付", "面容支付", "免密支付",
    "转账金额", "确认转账", "收款方", "付款方式", "付款账户", "收款账户",
    "提交订单", "去结算", "确认下单", "订单金额", "实付款", "应付金额",
    "信用卡还款", "钱包余额", "话费充值", "游戏充值", "到账金额", "确认付款",
)

# 资金类包名（与手机端 BLOCKED_PACKAGES 同源）。
BLOCKED_PACKAGES: frozenset[str] = frozenset({
    "com.eg.android.AlipayGphone",
    "com.unionpay",
    "cn.gov.pbc.dcep",
    "com.jd.jrapp",
    "com.antfortune.wealth",
    "com.icbc",
    "com.chinamworld.main",
    "com.chinamworld.bocmbci",
    "com.android.bankabc",
    "com.bankcomm.Bankcomm",
    "com.cmbchina.ccd.pluto.cmbActivity",
    "com.spdbccc.app",
    "com.cgbchina.xpt",
    "com.ecitic.bank.mobile",
    "com.pingan.paces.ccms",
    "com.cebbank.mobile.cemb",
    "com.yitong.mbank.psbc",
    "com.cmbchina.creditcard",
    "com.hexin.plat.android",
    "com.eastmoney.android.berlin",
    "com.htsc.mdc",
    "com.guosen.android",
    "com.lphtsccft",
    "com.miui.wallet",
    "com.huawei.wallet",
    "com.oplus.wallet",
    "com.vivo.wallet",
    "com.samsung.android.spay",
    "com.google.android.apps.walletnfcrel",
    "com.paypal.android.p2pmobile",
    "com.binance.dev",
    "com.okinc.okex.gp",
    "com.coinbase.android",
})


class MoneyBlocked(Exception):
    """命中资金保护。上层转成 ToolError，让模型立刻停手。"""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


# 资源 id 里的英文词。按 `_`/`-`/`.`/驼峰切词后整词比对，
# 避免 "pay" 命中 "display" 这类误伤。
MONEY_ID_TOKENS: frozenset[str] = frozenset({
    "pay", "payment", "paypal", "transfer", "remit", "recharge", "withdraw",
    "checkout", "settle", "settlement", "buy", "purchase", "topup",
    "wallet", "balance", "bankcard", "creditcard",
})


def _id_tokens(raw: str) -> set[str]:
    import re

    lowered = re.sub(r"([a-z])([A-Z])", r"\1_\2", raw).lower()
    return {t for t in re.split(r"[^a-z0-9]+", lowered) if t}


def _locators(payload: dict[str, Any]) -> list[str]:
    """只取**定位目标**的参数，不取要输入的文字。

    `value` 刻意不查：往聊天框里打「转账」两个字是正常需求，拦掉只会让人没法用。
    真正危险的是「在支付页上输入」——那由手机端的整屏判定负责。
    """
    keys = ("node_id", "text", "desc", "view_id", "class_name", "package")
    return [str(payload[k]) for k in keys if isinstance(payload.get(k), str)]


def check(path: str, payload: dict[str, Any] | None) -> None:
    """在请求发出前检查参数。命中就抛 MoneyBlocked。"""
    if not payload:
        return

    pkg = payload.get("package")
    if isinstance(pkg, str) and pkg in BLOCKED_PACKAGES:
        raise MoneyBlocked(f"「{pkg}」是资金类应用，禁止远程操作")
    for s in _locators(payload):
        hit = next((k for k in MONEY_KEYWORDS if k in s), None)
        if hit is None and _id_tokens(s) & MONEY_ID_TOKENS:
            hit = ",".join(sorted(_id_tokens(s) & MONEY_ID_TOKENS))
        if hit is not None:
            raise MoneyBlocked(
                f"目标涉及资金操作（命中「{hit}」），已拒绝执行。"
                "不要换个说法重试——这类操作必须由用户本人在手机上完成。"
            )
