package top.rainaki.phonemcp.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 屏幕文字识别。
 *
 * ## 为什么需要
 *
 * 无障碍树只能读到「用了标准控件」的界面。以下场景树里是空的：
 *   * 游戏（SurfaceView / OpenGL 自绘）
 *   * Canvas 绘制的自定义 UI
 *   * Flutter / React Native 用自定义渲染器画的组件
 *   * 部分 WebView 里的跨域内容
 *
 * 这些界面模型只能看截图，然后**盲点坐标**——点没点中全凭运气。
 * OCR 把截图里的文字连位置一起提取出来，就能像树节点一样按文本定位。
 *
 * ## 为什么用 bundled 模型
 *
 * `play-services-mlkit-text-recognition-chinese` 的模型要由 Google Play 服务
 * 下载，国产 ROM 上经常没有 Play 服务，模型永远下不下来。
 * `com.google.mlkit:text-recognition-chinese` 把模型打包进 APK（约 4MB），
 * 离线可用，代价是包大一点。
 */
object Ocr {

    /** 一块识别出的文字及其在屏幕上的位置。 */
    data class Line(
        val text: String,
        val bounds: Rect,
        /** 置信度（ML Kit 目前不提供，保留字段）。 */
        val confidence: Float = 0f,
    )

    /** 识别超时。慢设备上首次调用要加载模型，给足时间。 */
    private const val TIMEOUT_SEC = 15L

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别一张位图里的所有文字行。**必须在后台线程调用**（内部会阻塞等待）。
     *
     * @param scale 位图相对屏幕的缩放比。截图常被缩小以省流量，
     *   返回的坐标要乘回去才是屏幕原生坐标。
     */
    fun recognize(bitmap: Bitmap, scale: Float = 1f): List<Line> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<List<Line>>(emptyList())
        val failure = AtomicReference<String?>(null)

        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = ArrayList<Line>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val content = line.text?.trim().orEmpty()
                        if (content.isEmpty()) continue
                        lines.add(
                            Line(
                                text = content,
                                bounds = Rect(
                                    (box.left * scale).toInt(),
                                    (box.top * scale).toInt(),
                                    (box.right * scale).toInt(),
                                    (box.bottom * scale).toInt(),
                                ),
                            )
                        )
                    }
                }
                result.set(lines)
                latch.countDown()
            }
            .addOnFailureListener { e ->
                failure.set(e.message ?: e.javaClass.simpleName)
                latch.countDown()
            }

        if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            throw IllegalStateException("文字识别超时（${TIMEOUT_SEC}s），设备可能太慢或模型未就绪")
        }
        failure.get()?.let { throw IllegalStateException("文字识别失败：$it") }
        return result.get()
    }

    /**
     * 按文本查找。先精确匹配，再退化为包含匹配。
     *
     * @param ignoreCase 英文/数字忽略大小写
     */
    fun find(lines: List<Line>, text: String, ignoreCase: Boolean = true): List<Line> {
        val target = text.trim()
        if (target.isEmpty()) return emptyList()
        val exact = lines.filter { it.text.equals(target, ignoreCase) }
        if (exact.isNotEmpty()) return exact
        return lines.filter { it.text.contains(target, ignoreCase) }
    }

    /** 中心点坐标，供点击用。 */
    fun center(line: Line): Pair<Float, Float> =
        line.bounds.centerX().toFloat() to line.bounds.centerY().toFloat()
}
