package shop.terramoor.github.io.lyrics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** TTML is timing metadata here. Untimed TTML still falls back to track info. */
internal object TtmlEnds {
    private val clock = Regex("""(?:(\d{1,4}):)?(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?""")
    private val offset = Regex("""(\d{1,7}(?:\.\d{1,3})?)(ms|s)?""")

    fun parse(text: String): Map<Long, Long> {
        if (text.isBlank() || text.contains("<!DOCTYPE", ignoreCase = true)) return emptyMap()
        return runCatching {
            val parser = Xml.newPullParser().apply { setInput(text.reader()) }
            val ends = mutableMapOf<Long, Long>()
            val inheritedStarts = mutableListOf(0L)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && ends.size < 4000) {
                if (event == XmlPullParser.START_TAG) {
                    require(parser.depth <= 64)
                    val parent = inheritedStarts.last()
                    val rawBegin = parser.getAttributeValue(null, "begin")
                    val explicitStart = timestamp(rawBegin)
                    val start = when {
                        explicitStart == null -> parent
                        rawBegin?.contains(':') == true || parent <= 0L || explicitStart >= parent -> explicitStart
                        else -> parent + explicitStart
                    }
                    inheritedStarts.add(start)
                    if (parser.name == "p" && explicitStart != null) {
                        val rawEnd = parser.getAttributeValue(null, "end")
                        val parsedEnd = timestamp(rawEnd)
                        val end = when {
                            parsedEnd != null -> if (rawEnd?.contains(':') == true || parent <= 0L || parsedEnd >= start) parsedEnd else parent + parsedEnd
                            else -> timestamp(parser.getAttributeValue(null, "dur"))?.plus(start)
                        }
                        if (end != null && end > start) ends.putIfAbsent(start, end)
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    inheritedStarts.removeAt(inheritedStarts.lastIndex)
                }
                event = parser.next()
            }
            ends
        }.getOrDefault(emptyMap())
    }

    internal fun timestamp(value: String?): Long? {
        val raw = value?.trim() ?: return null
        if (raw.isEmpty()) return null
        clock.matchEntire(raw)?.destructured?.let { (hour, minute, second, fraction) ->
            if (second.toInt() >= 60 || (hour.isNotEmpty() && minute.toInt() >= 60)) return null
            val seconds = (hour.toLongOrNull() ?: 0) * 3600 + minute.toLong() * 60 + second.toLong()
            return seconds * 1000 + fraction.padEnd(3, '0').toLong()
        }
        val (number, unit) = offset.matchEntire(raw)?.destructured ?: return null
        val multiplier = if (unit == "ms") 1.0 else 1000.0
        return (number.toDouble() * multiplier).toLong()
    }
}
