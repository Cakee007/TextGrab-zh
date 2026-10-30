package dev.noah.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin wrapper around ML Kit's bundled on-device text recognizers.
 *
 * ## Why two models
 *
 * The Chinese pack is trained on hanzi; it reads digits, English and symbols
 * only as a side effect and is noticeably weaker at them. The Latin pack is
 * the accurate one for those, but equally weak at Chinese. Shipping only one
 * therefore always leaves half the screen degraded, so both are bundled and
 * every region is taken from the model that reads it best.
 *
 * Both models ship inside the APK, so recognition works fully offline and
 * without Google Play services.
 */
object OcrEngine {

    /**
     * Longest edge handed to the model. Very large screenshots make ML Kit drop
     * lines: its internal detector works on a bounded raster, so a 3000 px tall
     * image ends up downsampled by the model itself with no control over the
     * filter. Scaling once here, with bilinear filtering, keeps the text sharp
     * and the boxes in a known coordinate space.
     */
    private const val MAX_EDGE = 2048

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

    /** A line as returned by one model, before the two are merged. */
    private class RawLine(
        val text: String,
        val box: RectF,
        val elements: List<Pair<String, RectF>>,
    )

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun warmUp() {
        // Trigger lazy init + model load off the critical path.
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val image = InputImage.fromBitmap(bmp, 0)
        chinese.process(image).addOnCompleteListener { bmp.recycle() }
        latin.process(image)
    }

    suspend fun recognize(bitmap: Bitmap): Result {
        val prepared = prepare(bitmap)
        val (image, scale) = prepared

        val (zhText, enText) = coroutineScope {
            val zh = async { runCatching { chinese.awaitText(image) }.getOrNull() }
            val en = async { runCatching { latin.awaitText(image) }.getOrNull() }
            zh.await() to en.await()
        }

        if (zhText == null && enText == null) {
            throw IllegalStateException("Text recognition failed")
        }

        val merged = merge(
            zhLines = linesOf(zhText, scale),
            enLines = linesOf(enText, scale),
        )
        return merged
    }

    /**
     * Scales the bitmap down when it exceeds [MAX_EDGE], returning the image
     * for ML Kit plus the factor needed to map boxes back to the original
     * pixels.
     */
    private fun prepare(bitmap: Bitmap): Pair<InputImage, Float> {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) {
            return InputImage.fromBitmap(bitmap, 0) to 1f
        }
        val ratio = MAX_EDGE.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        // Bilinear filtering keeps glyph edges intact, unlike inSampleSize which
        // throws pixels away before any averaging happens.
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        return InputImage.fromBitmap(scaled, 0) to (bitmap.width.toFloat() / w)
    }

    private suspend fun TextRecognizer.awaitText(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    private fun linesOf(text: Text?, scale: Float): List<RawLine> {
        if (text == null) return emptyList()
        val out = ArrayList<RawLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox?.let {
                    RectF(
                        it.left * scale,
                        it.top * scale,
                        it.right * scale,
                        it.bottom * scale,
                    )
                } ?: continue
                val elements = line.elements.mapNotNull { e ->
                    val b = e.boundingBox ?: return@mapNotNull null
                    if (e.text.isBlank()) return@mapNotNull null
                    e.text to RectF(
                        b.left * scale,
                        b.top * scale,
                        b.right * scale,
                        b.bottom * scale,
                    )
                }
                if (elements.isEmpty()) continue
                out.add(RawLine(line.text, box, elements))
            }
        }
        return out
    }

    /**
     * Picks, for every line, whichever model read it better.
     *
     * A line that contains hanzi can only come from the Chinese pack with any
     * confidence, and a line made of digits/Latin/symbols is better read by the
     * Latin pack. Lines found by only one model are taken as they are.
     */
    private fun merge(zhLines: List<RawLine>, enLines: List<RawLine>): Result {
        val chosen = ArrayList<RawLine>()

        // Match lines that both models saw, so the better reading wins.
        val usedEn = HashSet<Int>()
        for (zh in zhLines) {
            var enIndex = -1
            for (i in enLines.indices) {
                if (i in usedEn) continue
                if (overlap(zh.box, enLines[i].box) > 0.5f) {
                    enIndex = i
                    break
                }
            }
            if (enIndex >= 0) {
                val en = enLines[enIndex]
                val zhHasCjk = zh.text.any { isCjkChar(it) }
                val enHasCjk = en.text.any { isCjkChar(it) }
                val pick = when {
                    zhHasCjk && !enHasCjk -> zh
                    enHasCjk && !zhHasCjk -> en
                    enHasCjk && zhHasCjk -> if (en.text.length > zh.text.length) en else zh
                    else -> if (scoreLatin(en.text) > scoreLatin(zh.text)) en else zh
                }
                usedEn.add(enIndex)
                chosen.add(pick)
            } else {
                chosen.add(zh)
            }
        }
        enLines.forEachIndexed { i, line ->
            if (!usedEn.contains(i)) chosen.add(line)
        }

        chosen.sortWith(compareBy({ it.box.top }, { it.box.left }))

        val words = ArrayList<Word>()
        val lineBoxes = ArrayList<RectF>()
        var lineId = 0
        var blockId = 0
        var lastTop = Float.NEGATIVE_INFINITY
        for (line in chosen) {
            // Start a new "block" whenever reading order jumps upward, which
            // keeps column layouts from being glued into one paragraph.
            if (line.box.top < lastTop - line.box.height() * 0.5f) blockId++
            lastTop = line.box.top

            lineBoxes.add(line.box)
            for ((text, box) in line.elements) {
                words.add(Word(text, box, lineId, blockId))
            }
            lineId++
        }
        return Result(words, lineBoxes)
    }

    /** Fraction of the smaller box's area that both boxes cover. */
    private fun overlap(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        val smaller = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else inter / smaller
    }

    /**
     * How plausible a reading is for text without Chinese characters: digits
     * and plain words count, stray punctuation and lone glyphs do not.
     */
    private fun scoreLatin(text: String): Int {
        var score = 0
        for (c in text) {
            when {
                c.isDigit() -> score += 2
                c in 'A'..'Z' || c in 'a'..'z' -> score += 1
                c.isWhitespace() -> score += 0
                c.isLetterOrDigit() -> score += 1
                else -> score -= 1
            }
        }
        // Reward runs, penalise strings that look like noise.
        return score - text.count { it in "|_~`^" } * 2
    }

    private fun isCjkChar(c: Char): Boolean {
        val code = c.code
        return code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0x3000..0x303F ||
            code in 0xFF00..0xFFEF ||
            code in 0xAC00..0xD7AF
    }
}
