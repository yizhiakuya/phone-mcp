package top.rainaki.phonemcp.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 极简 Markdown 渲染。
 *
 * 只支持 release notes 里实际会出现的语法：标题、粗体、行内代码、列表、分隔线、
 * 段落。**刻意不引第三方库**——完整 Markdown 解析器动辄几百 KB，而这里只需要
 * 把 `## 标题` 和 `**粗体**` 显示正常，不要露出源码。
 *
 * 不支持的就原样显示（比如链接、表格、图片），比渲染错好。
 */
object MarkdownView {

    private const val COLOR_HEADING = 0xFF1A1A1A.toInt()
    private const val COLOR_BODY = 0xFF333333.toInt()
    private const val COLOR_CODE_BG = 0xFFF2F3F5.toInt()
    private const val COLOR_MUTED = 0xFF6B7280.toInt()

    /** 把 Markdown 文本渲染成一个可滚动的 View。 */
    fun render(context: Context, markdown: String): ScrollView {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 20)
            setPadding(pad, dp(context, 8), pad, dp(context, 8))
        }

        var i = 0
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isBlank()) {
                paragraph.setLength(0)
                return
            }
            column.addView(bodyText(context, paragraph.toString().trim()))
            paragraph.setLength(0)
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            when {
                // 代码块：整段原样显示（release notes 里主要是 ```meta，通常已被剥掉）
                line.trimStart().startsWith("```") -> {
                    flushParagraph()
                    val lang = line.trim().removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.appendLine(lines[i])
                        i++
                    }
                    if (code.isNotEmpty()) {
                        column.addView(codeBlock(context, code.toString().trimEnd(), lang))
                    }
                }

                line.isBlank() -> flushParagraph()

                line.matches(Regex("^#{1,6}\\s+.*")) -> {
                    flushParagraph()
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.dropWhile { it == '#' }.trim()
                    column.addView(headingText(context, text, level))
                }

                line.matches(Regex("^([-*_])\\1{2,}$")) -> {
                    flushParagraph()
                    column.addView(divider(context))
                }

                line.matches(Regex("^\\s*[-*+]\\s+.*")) -> {
                    flushParagraph()
                    val text = line.trimStart().removePrefix("- ")
                        .removePrefix("* ").removePrefix("+ ")
                    column.addView(bulletText(context, text))
                }

                line.matches(Regex("^\\s*\\d+[.)]\\s+.*")) -> {
                    flushParagraph()
                    val num = line.trimStart().takeWhile { it.isDigit() }
                    val text = line.trimStart().substringAfter(" ").trim()
                    column.addView(bulletText(context, text, "$num. "))
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flushParagraph()

        return ScrollView(context).apply {
            addView(column)
            isFillViewport = true
        }
    }

    // ---------------------------------------------------------------- 元素

    private fun headingText(context: Context, text: String, level: Int): TextView =
        TextView(context).apply {
            this.text = inline(text)
            setTextColor(COLOR_HEADING)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                when (level) {
                    1 -> 20f
                    2 -> 17f
                    3 -> 15f
                    else -> 14f
                },
            )
            setPadding(0, dp(context, if (level <= 2) 16 else 12), 0, dp(context, 6))
        }

    private fun bodyText(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = inline(text)
            setTextColor(COLOR_BODY)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

    private fun bulletText(context: Context, text: String, marker: String = "• "): TextView =
        TextView(context).apply {
            val body = inline(text)
            val sb = SpannableStringBuilder()
            sb.append(marker)
            sb.append(body)
            // 悬挂缩进：换行时文字对齐到标记之后
            sb.setSpan(
                BulletSpan(dp(context, 14)),
                0,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            this.text = sb
            setTextColor(COLOR_BODY)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setPadding(dp(context, 4), dp(context, 2), 0, dp(context, 2))
        }

    private fun codeBlock(context: Context, code: String, lang: String): TextView =
        TextView(context).apply {
            text = code
            setTextColor(COLOR_MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(COLOR_CODE_BG)
            val p = dp(context, 10)
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(context, 6)
            lp.bottomMargin = dp(context, 6)
            layoutParams = lp
            if (lang.isNotBlank() && lang != "meta") {
                // 语言标记不显示，避免干扰
            }
        }

    private fun divider(context: Context): android.view.View =
        android.view.View(context).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1),
            ).apply { topMargin = dp(context, 10); bottomMargin = dp(context, 10) }
        }

    // ---------------------------------------------------------------- 行内

    /**
     * 处理行内语法：`**粗体**`、`*斜体*`、`` `代码` ``。
     *
     * 用单遍扫描而不是链式 replace——链式替换会在嵌套/重叠时出错。
     */
    private fun inline(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        val start = sb.length
                        sb.append(text, i + 2, end)
                        sb.setSpan(
                            StyleSpan(Typeface.BOLD), start, sb.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        i = end + 2
                    } else {
                        sb.append(text[i]); i++
                    }
                }

                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        val start = sb.length
                        sb.append(text, i + 1, end)
                        sb.setSpan(
                            TypefaceSpan("monospace"), start, sb.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        sb.setSpan(
                            ForegroundColorSpan(0xFF9A3412.toInt()), start, sb.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        i = end + 1
                    } else {
                        sb.append(text[i]); i++
                    }
                }

                text[i] == '*' && i + 1 < text.length && text[i + 1] != '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        val start = sb.length
                        sb.append(text, i + 1, end)
                        sb.setSpan(
                            StyleSpan(Typeface.ITALIC), start, sb.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        i = end + 1
                    } else {
                        sb.append(text[i]); i++
                    }
                }

                else -> {
                    sb.append(text[i]); i++
                }
            }
        }
        return sb
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
