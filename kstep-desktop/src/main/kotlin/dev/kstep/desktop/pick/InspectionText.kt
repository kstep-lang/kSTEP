package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartInfo
import java.util.Locale
import kotlin.math.abs

/** Text of the inspection panel. Locale-independent: always a dot and two decimals. */
object InspectionText {
    private const val THOUSANDS_SEPARATOR = ','

    fun number(v: Double): String {
        val text = String.format(Locale.ROOT, "%.2f", v)
        // no "-0.00"
        return if (text == "-0.00") "0.00" else text
    }

    /** `W x D x H mm` of the part's bounding box. */
    fun size(p: PartInfo): String =
        (0 until 3).joinToString(" × ") { number(abs(p.bboxMax[it] - p.bboxMin[it])) } + " mm"

    fun point(v: DoubleArray): String = v.joinToString(", ", "(", ")") { number(it) } + " mm"

    /** Triangle count with a comma as thousands separator. */
    fun triangles(n: Int): String {
        val digits = n.toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 &&
                (digits.length - i) % 3 == 0 &&
                ch != '-' &&
                digits[i - 1] != '-'
            ) {
                sb.append(THOUSANDS_SEPARATOR)
            }
            sb.append(ch)
        }
        return sb.toString()
    }
}
