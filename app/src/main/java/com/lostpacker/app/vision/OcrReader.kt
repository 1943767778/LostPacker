package com.lostpacker.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用 Ml Kit 离线 OCR 读取图标右下角/指定区域的堆叠数量数字。
 * Ml Kit 是异步 API，这里用 CountDownLatch 阻塞等待（供整理线程同步使用）。
 */
object OcrReader {

    private val recognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** 识别 [region]（源图坐标）内第一个 1~4 位整数，解析失败返回 null。 */
    fun readCount(bmp: Bitmap, region: Rect): Int? {
        if (region.width() <= 1 || region.height() <= 1) return null
        val crop = try {
            Bitmap.createBitmap(
                bmp,
                region.left.coerceIn(0, bmp.width - 1),
                region.top.coerceIn(0, bmp.height - 1),
                region.width().coerceIn(1, bmp.width - region.left.coerceIn(0, bmp.width - 1)),
                region.height().coerceIn(1, bmp.height - region.top.coerceIn(0, bmp.height - 1))
            )
        } catch (e: Exception) { return null }
        return readNumber(crop)
    }

    private fun readNumber(bmp: Bitmap): Int? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Int>(1)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                holder[0] = text.textBlocks.asSequence()
                    .map { it.text }
                    .joinToString(" ")
                    .filter(Char::isDigit)
                    .let { if (it.isEmpty()) null else it.toIntOrNull() }
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }
        try { latch.await(4, TimeUnit.SECONDS) } catch (e: InterruptedException) {}
        return holder[0]
    }

    /** 在全屏截图 [bmp] 中找到包含 [keyword] 的文字块中心点（源图坐标）；找不到返回 null。 */
    fun findText(bmp: Bitmap, keyword: String): android.graphics.Point? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<android.graphics.Point>(1)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                for (block in text.textBlocks) {
                    if (block.text.contains(keyword)) {
                        val r = block.boundingBox
                        if (r != null) {
                            holder[0] = android.graphics.Point(r.centerX(), r.centerY())
                            break
                        }
                    }
                }
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }
        try { latch.await(4, TimeUnit.SECONDS) } catch (e: InterruptedException) {}
        return holder[0]
    }
}