package dev.noah.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin wrapper around ML Kit's bundled on-device text recognizer.
 *
 * Only the Chinese pack is bundled. It is trained on Chinese but also covers
 * Latin letters, digits and common punctuation, so mixed 中英 screenshots
 * come out correctly while keeping the APK about 20 MB smaller than shipping
 * both models.
 *
 * The model ships inside the APK, so recognition works fully offline and
 * without Google Play services (GrapheneOS etc.).
 */
object OcrEngine {

    /** One recognized word with its bounding box in bitmap pixel coordinates. */
    data class Word(
        val text: String,
        val box: RectF,
        val lineId: Int,
        val blockId: Int,
    )

    data class Result(
        val words: List<Word>,
        val lineBoxes: List<RectF>,
    ) {
        val isEmpty get() = words.isEmpty()

        /**
         * Joins a word range [start..end] back into readable text.
         * CJK text takes no space between adjacent pieces, Latin text does.
         */
        fun textOf(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start..end) {
                if (i > start) {
                    val prev = words[i - 1]
                    val cur = words[i]
                    sb.append(
                        when {
                            prev.blockId != cur.blockId -> "\n\n"
                            prev.lineId != cur.lineId -> "\n"
                            isCjkBoundary(prev.text, cur.text) -> ""
                            else -> " "
                        }
                    )
                }
                sb.append(words[i].text)
            }
            return sb.toString()
        }

        val fullText: String get() = if (isEmpty) "" else textOf(0, words.size - 1)

        /** No separator needed when either side of the join is a CJK character. */
        private fun isCjkBoundary(left: String, right: String): Boolean {
            val l = left.lastOrNull() ?: return false
            val r = right.firstOrNull() ?: return false
            return isCjk(l) || isCjk(r)
        }

        private fun isCjk(c: Char): Boolean {
            val code = c.code
            return code in 0x3040..0x30FF ||   // kana
                code in 0x3400..0x4DBF ||      // CJK ext A
                code in 0x4E00..0x9FFF ||      // CJK unified
                code in 0xF900..0xFAFF ||      // compatibility ideographs
                code in 0x3000..0x303F ||      // CJK punctuation
                code in 0xFF00..0xFFEF ||      // fullwidth forms
                code in 0xAC00..0xD7AF        // hangul syllables
        }
    }

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun warmUp() {
        // Trigger lazy init + model load off the critical path.
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { bmp.recycle() }
    }

    suspend fun recognize(bitmap: Bitmap): Result =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val words = ArrayList<Word>()
                    val lineBoxes = ArrayList<RectF>()
                    var lineId = 0
                    text.textBlocks.forEachIndexed { blockId, block ->
                        for (line in block.lines) {
                            line.boundingBox?.let { lineBoxes.add(RectF(it)) }
                            for (element in line.elements) {
                                val box = element.boundingBox ?: continue
                                if (element.text.isBlank()) continue
                                words.add(Word(element.text, RectF(box), lineId, blockId))
                            }
                            lineId++
                        }
                    }
                    cont.resume(Result(words, lineBoxes))
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
