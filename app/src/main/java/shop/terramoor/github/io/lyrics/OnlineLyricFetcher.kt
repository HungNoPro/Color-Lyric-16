package shop.terramoor.github.io.lyrics

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal object OnlineLyricFetcher {
    private const val TAG = "TerraMoorOnline"
    private const val CONNECT_TIMEOUT_MS = 4500
    private const val READ_TIMEOUT_MS = 6000
    private const val USER_AGENT = "ColorLyric-COS16/1.9 (Android)"

    data class FetchedPayload(
        val source: String,
        val payloadJson: String,
    )

    fun fetchLyrics(
        context: Context, title: String, artist: String?, durationMs: Long,
        sourceMode: String = "AUTO",
    ): FetchedPayload? {
        if (sourceMode == "EXTERNAL_ONLY") return null
        val cleanTitle = sanitizeTrackTitle(title)
        if (cleanTitle.isEmpty()) return null
        val cleanArtist = artist?.trim().orEmpty()
        val cacheKey = sha256("lyrics|$sourceMode|$cleanTitle|$cleanArtist|${durationMs / 1000}")
        readCache(context, cacheKey)?.let { cached ->
            val obj = try { JSONObject(cached) } catch (_: Exception) { null }
            if (obj != null && obj.has("payload")) {
                return FetchedPayload(obj.optString("source", "cache"), obj.getString("payload"))
            }
        }

        val wordSyncFirst = listOf<Pair<String, () -> FetchedPayload?>>(
            "APPLE_MUSIC" to { tryAppleMusic(cleanTitle, cleanArtist) },
            "BETTER_LYRICS" to { tryBetterLyrics(cleanTitle, cleanArtist) },
            "LRCLIB" to { tryLrclib(cleanTitle, cleanArtist, durationMs) },
            "KUGOU" to { tryKugou(cleanTitle, cleanArtist, durationMs) },
            "LYRICS_OVH" to { tryLyricsOvh(cleanTitle, cleanArtist, durationMs) },
        )
        val ordered = if (sourceMode == "AUTO") {
            wordSyncFirst
        } else {
            val preferred = wordSyncFirst.filter { it.first == sourceMode }
            val fallback = wordSyncFirst.filter { it.first != sourceMode }
            preferred + fallback
        }
        val fetched = ordered.firstNotNullOfOrNull { it.second() }

        if (fetched != null) {
            val wrap = JSONObject()
                .put("source", fetched.source)
                .put("payload", fetched.payloadJson)
            writeCache(context, cacheKey, wrap.toString())
        }
        return fetched
    }

    fun clearCache(context: Context): Int {
        val dir = File(context.cacheDir, "online_lyrics_cache")
        if (!dir.exists()) return 0
        var count = 0
        dir.listFiles()?.forEach { if (it.delete()) count++ }
        return count
    }

    fun translateLines(context: Context, lines: List<String>, targetLang: String = "vi"): List<String>? {
        if (lines.isEmpty()) return null
        if (targetLang == "vi" && looksVietnamese(lines)) {
            Log.i(TAG, "Skip translation: lyrics already Vietnamese")
            return null
        }

        val cacheKey = sha256("tr|$targetLang|" + lines.joinToString("\n"))
        readCache(context, cacheKey)?.let { cached ->
            try {
                val arr = JSONArray(cached)
                if (arr.length() == lines.size) {
                    return List(arr.length()) { arr.optString(it, "") }
                }
            } catch (_: Exception) {
            }
        }

        val result = MutableList(lines.size) { "" }
        val nonEmptyIndices = lines.indices.filter { lines[it].isNotBlank() }
        if (nonEmptyIndices.isEmpty()) return null

        // Batch lines in chunks (~1200 chars each) so Google Translate keeps 1-to-1 line alignment.
        var cursor = 0
        var anyTranslated = false
        while (cursor < nonEmptyIndices.size) {
            val batchIndices = ArrayList<Int>()
            val batchText = StringBuilder()
            while (cursor < nonEmptyIndices.size && batchIndices.size < 25) {
                val idx = nonEmptyIndices[cursor]
                val line = lines[idx].replace('\n', ' ').trim()
                if (batchText.length + line.length + 1 > 1200 && batchIndices.isNotEmpty()) break
                if (batchText.isNotEmpty()) batchText.append('\n')
                batchText.append(line)
                batchIndices.add(idx)
                cursor++
            }
            val translatedBatch = translateBlockGoogle(batchText.toString(), targetLang) ?: continue
            val split = translatedBatch.split('\n')
            for (i in batchIndices.indices) {
                val tr = split.getOrNull(i)?.trim().orEmpty()
                if (tr.isNotEmpty()) {
                    result[batchIndices[i]] = tr
                    anyTranslated = true
                }
            }
        }

        if (!anyTranslated) return null
        val jsonArr = JSONArray()
        result.forEach { jsonArr.put(it) }
        writeCache(context, cacheKey, jsonArr.toString())
        return result
    }

    // 0. Apple Music (iTunes track lookup + Paxsenix Apple Music TTML word-by-word)
    private fun tryAppleMusic(title: String, artist: String): FetchedPayload? {
        return try {
            val term = if (artist.isNotBlank()) "$title $artist" else title
            val searchUrl = "https://itunes.apple.com/search?term=${enc(term)}&entity=song&limit=5"
            val searchBody = httpGet(searchUrl) ?: return null
            val results = JSONObject(searchBody).optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            var trackId: Long? = null
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optLong("trackId", 0L)
                if (id <= 0L) continue
                val tName = item.optString("trackName")
                val aName = item.optString("artistName")
                if (trackId == null) trackId = id
                if (tName.contains(title, ignoreCase = true) &&
                    (artist.isBlank() || aName.contains(artist, ignoreCase = true))) {
                    trackId = id
                    break
                }
            }
            val resolvedId = trackId ?: return null
            val lyricBody = httpGet("https://lyrics.paxsenix.org/apple-music/lyrics?id=$resolvedId") ?: return null
            val obj = JSONObject(lyricBody)
            val ttml = obj.optString("ttml").takeIf { it.isNotBlank() }
                ?: obj.optString("ttmlContent").takeIf { it.isNotBlank() }
            if (ttml != null) {
                val lrc = ttmlToBasicLrc(ttml)
                if (lrc.isNotBlank()) {
                    val payload = JSONObject()
                        .put("songName", title)
                        .put("artist", artist)
                        .put("lyric", lrc)
                        .put("ttml", ttml)
                        .toString()
                    Log.i(TAG, "Fetched TTML word-sync lyrics from Apple Music (id=$resolvedId)")
                    return FetchedPayload("apple-music", payload)
                }
            }
            val elrc = obj.optString("elrc").takeIf { it.isNotBlank() }
                ?: obj.optString("lrc").takeIf { it.isNotBlank() }
                ?: return null
            Log.i(TAG, "Fetched LRC lyrics from Apple Music (id=$resolvedId)")
            FetchedPayload("apple-music", buildLrcPayload(title, artist, elrc))
        } catch (e: Exception) {
            Log.w(TAG, "Apple Music failed: ${e.message}")
            null
        }
    }

    // 1. Better Lyrics (TTML / synced)
    private fun tryBetterLyrics(title: String, artist: String): FetchedPayload? {
        return try {
            val url = "https://lyrics-api.boidu.dev/getLyrics?s=${enc(title)}&a=${enc(artist)}"
            val body = httpGet(url) ?: return null
            val json = JSONObject(body)
            val ttml = json.optString("ttml").takeIf { it.isNotBlank() } ?: return null
            val lrc = ttmlToBasicLrc(ttml)
            if (lrc.isBlank()) return null
            val payload = JSONObject()
                .put("songName", title)
                .put("artist", artist)
                .put("lyric", lrc)
                .put("ttml", ttml)
                .toString()
            Log.i(TAG, "Fetched lyrics from Better Lyrics (${lrc.length} chars)")
            FetchedPayload("better-lyrics", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Better Lyrics failed: ${e.message}")
            null
        }
    }

    // 2. LRCLIB (synced LRC)
    private fun tryLrclib(title: String, artist: String, durationMs: Long): FetchedPayload? {
        try {
            val sb = StringBuilder("https://lrclib.net/api/get?track_name=${enc(title)}")
            if (artist.isNotBlank()) sb.append("&artist_name=${enc(artist)}")
            if (durationMs > 0L) sb.append("&duration=${durationMs / 1000}")
            httpGet(sb.toString())?.let { body ->
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (synced != null) {
                    val payload = buildLrcPayload(title, artist, synced)
                    Log.i(TAG, "Fetched synced lyrics from LRCLIB /get")
                    return FetchedPayload("lrclib", payload)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB /get failed: ${e.message}")
        }

        return try {
            val q = if (artist.isNotBlank()) "$title $artist" else title
            val body = httpGet("https://lrclib.net/api/search?q=${enc(q)}") ?: return null
            val arr = JSONArray(body)
            var bestSynced: String? = null
            var bestPlain: String? = null
            for (i in 0 until minOf(arr.length(), 8)) {
                val item = arr.optJSONObject(i) ?: continue
                val synced = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (synced != null) {
                    bestSynced = synced
                    break
                }
                if (bestPlain == null) {
                    bestPlain = item.optString("plainLyrics").takeIf { it.isNotBlank() }
                }
            }
            val lrc = bestSynced ?: bestPlain?.let { plainToSyntheticLrc(it, durationMs) } ?: return null
            Log.i(TAG, "Fetched lyrics from LRCLIB /search (synced=${bestSynced != null})")
            FetchedPayload("lrclib", buildLrcPayload(title, artist, lrc))
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB /search failed: ${e.message}")
            null
        }
    }

    // 3. Kugou
    private fun tryKugou(title: String, artist: String, durationMs: Long): FetchedPayload? {
        return try {
            val kw = if (artist.isNotBlank()) "$title-$artist" else title
            val dur = if (durationMs > 0L) durationMs else 240_000L
            val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(kw)}&duration=$dur"
            val searchBody = httpGet(searchUrl) ?: return null
            val candidates = JSONObject(searchBody).optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val best = candidates.optJSONObject(0) ?: return null
            val id = best.optString("id")
            val accesskey = best.optString("accesskey")
            if (id.isBlank() || accesskey.isBlank()) return null

            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(accesskey)}&fmt=lrc&charset=utf8"
            val dlBody = httpGet(dlUrl) ?: return null
            val contentB64 = JSONObject(dlBody).optString("content")
            if (contentB64.isBlank()) return null
            val lrc = String(Base64.decode(contentB64, Base64.DEFAULT), StandardCharsets.UTF_8)
                .removePrefix("\uFEFF")
            if (lrc.isBlank()) return null
            Log.i(TAG, "Fetched lyrics from Kugou (${lrc.length} chars)")
            FetchedPayload("kugou", buildLrcPayload(title, artist, lrc))
        } catch (e: Exception) {
            Log.w(TAG, "Kugou failed: ${e.message}")
            null
        }
    }

    // 4. lyrics.ovh (plain text fallback)
    private fun tryLyricsOvh(title: String, artist: String, durationMs: Long): FetchedPayload? {
        if (artist.isBlank()) return null
        return try {
            val url = "https://api.lyrics.ovh/v1/${encPath(artist)}/${encPath(title)}"
            val body = httpGet(url) ?: return null
            val plain = JSONObject(body).optString("lyrics").takeIf { it.isNotBlank() } ?: return null
            val lrc = plainToSyntheticLrc(plain, durationMs)
            if (lrc.isBlank()) return null
            Log.i(TAG, "Fetched plain lyrics from lyrics.ovh")
            FetchedPayload("lyrics.ovh", buildLrcPayload(title, artist, lrc))
        } catch (e: Exception) {
            Log.w(TAG, "lyrics.ovh failed: ${e.message}")
            null
        }
    }

    private fun translateBlockGoogle(text: String, targetLang: String): String? {
        return try {
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${enc(targetLang)}&dt=t")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
            val form = "q=${enc(text)}"
            conn.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val root = JSONArray(body)
            val segments = root.optJSONArray(0) ?: return null
            val out = StringBuilder()
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                val piece = seg.optString(0)
                if (!piece.isNullOrEmpty() && piece != "null") out.append(piece)
            }
            out.toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Google Translate failed: ${e.message}")
            null
        }
    }

    private fun ttmlToBasicLrc(ttml: String): String {
        val pRegex = Regex("""<p\b[^>]*\bbegin="([^"]+)"[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tagRegex = Regex("""<[^>]+>""")
        val out = StringBuilder()
        for (match in pRegex.findAll(ttml)) {
            val ms = parseTtmlTimeMs(match.groupValues[1]) ?: continue
            val text = decodeXmlEntities(tagRegex.replace(match.groupValues[2], "")).replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            out.append(formatLrcTime(ms)).append(text).append('\n')
        }
        return out.toString()
    }

    private fun parseTtmlTimeMs(raw: String): Long? {
        val v = raw.trim()
        if (v.endsWith("s") && !v.endsWith("ms")) {
            return (v.dropLast(1).toDoubleOrNull()?.times(1000.0))?.toLong()
        }
        val parts = v.split(':')
        return when (parts.size) {
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                (m * 60_000L) + (s * 1000.0).toLong()
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                (h * 3_600_000L) + (m * 60_000L) + (s * 1000.0).toLong()
            }
            else -> v.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
    }

    private fun plainToSyntheticLrc(plain: String, durationMs: Long): String {
        val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val total = if (durationMs > 15_000L) durationMs - 4_000L else lines.size * 3_500L
        val step = (total / lines.size).coerceAtLeast(1_500L)
        val out = StringBuilder()
        lines.forEachIndexed { idx, line ->
            out.append(formatLrcTime(idx * step)).append(line).append('\n')
        }
        return out.toString()
    }

    private fun formatLrcTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val min = safe / 60_000L
        val sec = (safe % 60_000L) / 1000L
        val cs = (safe % 1000L) / 10L
        return String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, cs)
    }

    private fun decodeXmlEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")

    private fun buildLrcPayload(title: String, artist: String, lrc: String): String =
        JSONObject()
            .put("songName", title)
            .put("artist", artist)
            .put("lyric", lrc)
            .toString()

    private fun looksVietnamese(lines: List<String>): Boolean {
        val sample = lines.take(12).joinToString(" ")
        val viChars = "ăâđêôơưĂÂĐÊÔƠƯáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ"
        val hits = sample.count { it in viChars }
        return hits >= 4
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sanitizeTrackTitle(raw: String): String {
        var s = raw.trim()
        // Strip YouTube / MV suffixes like "| Official Lofi Lyrics", "(Official Video)", "[MV]"
        s = s.substringBefore("|").trim()
        s = s.replace(Regex("""\s*[\(\[](?:official|lyrics?|mv|audio|video|hd|4k|lofi|remix|live|feat\.|ft\.)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
        // If format is "Song Name - Artist1 x Artist2", keep "Song Name"
        if (s.contains(" - ")) {
            val left = s.substringBefore(" - ").trim()
            if (left.length >= 2) s = left
        }
        return s.trim()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun encPath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "online_lyrics_cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$key.json")
    }

    private fun readCache(context: Context, key: String): String? = try {
        val f = cacheFile(context, key)
        if (f.exists() && f.length() > 0L) f.readText(StandardCharsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    private fun writeCache(context: Context, key: String, content: String) {
        try {
            cacheFile(context, key).writeText(content, StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
