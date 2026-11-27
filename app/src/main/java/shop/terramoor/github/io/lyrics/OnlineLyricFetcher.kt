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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.math.abs

internal object OnlineLyricFetcher {
    private const val TAG = "TerraMoorOnline"
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 4500
    private const val PARALLEL_BUDGET_MS = 3800L
    private const val USER_AGENT = "ColorLyric-COS16/1.9 (Android)"
    private val KRC_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    private val fetchPool = Executors.newCachedThreadPool()

    data class FetchedPayload(
        val source: String,
        val payloadJson: String,
        val isTrueWordSync: Boolean = false,
    )

    data class CacheStats(
        val fileCount: Int,
        val totalBytes: Long,
    )

    fun fetchLyrics(
        context: Context, title: String, artist: String?, durationMs: Long,
        sourceMode: String = "AUTO",
    ): FetchedPayload? {
        if (sourceMode == "EXTERNAL_ONLY") return null
        val rawTitle = title.trim()
        val cleanTitle = sanitizeTrackTitle(rawTitle)
        if (cleanTitle.isEmpty()) return null
        val rawArtist = artist?.trim().orEmpty()
        val cacheKey = sha256("lyrics_v5|$sourceMode|$cleanTitle|$rawArtist|${durationMs / 1000}")
        readCache(context, cacheKey)?.let { cached ->
            val obj = try { JSONObject(cached) } catch (_: Exception) { null }
            if (obj != null && obj.has("payload")) {
                val src = obj.optString("source", "cache")
                val trueWord = if (obj.has("trueWordSync")) {
                    obj.optBoolean("trueWordSync", false)
                } else {
                    val low = src.lowercase(Locale.ROOT)
                    low == "apple-music" || low == "better-lyrics" || low == "kugou-krc" || low.startsWith("lyricsplus")
                }
                return FetchedPayload(src, obj.getString("payload"), isTrueWordSync = trueWord)
            }
        }

        val fetched = if (sourceMode == "AUTO") {
            fetchParallelAuto(rawTitle, cleanTitle, rawArtist, durationMs)
        } else {
            val wordSyncFirst = listOf<Pair<String, () -> FetchedPayload?>>(
                "APPLE_MUSIC" to { tryAppleMusic(rawTitle, cleanTitle, rawArtist, durationMs) },
                "BETTER_LYRICS" to { tryBetterLyrics(rawTitle, cleanTitle, rawArtist, durationMs) },
                "LRCLIB" to { tryLrclib(rawTitle, cleanTitle, rawArtist, durationMs) },
                "KUGOU" to { tryKugou(rawTitle, cleanTitle, rawArtist, durationMs) },
                "LYRICS_OVH" to { tryLyricsOvh(rawTitle, cleanTitle, rawArtist, durationMs) },
            )
            val preferred = wordSyncFirst.filter { it.first == sourceMode }
            val fallback = wordSyncFirst.filter { it.first != sourceMode }
            (preferred + fallback).firstNotNullOfOrNull { it.second() }
        }

        if (fetched != null) {
            val wrap = JSONObject()
                .put("source", fetched.source)
                .put("trueWordSync", fetched.isTrueWordSync)
                .put("payload", fetched.payloadJson)
            writeCache(context, cacheKey, wrap.toString())
        }
        return fetched
    }

    fun cacheStats(context: Context): CacheStats {
        val dir = File(context.cacheDir, "online_lyrics_cache")
        if (!dir.exists()) return CacheStats(0, 0L)
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        return CacheStats(files.size, files.sumOf { it.length() })
    }

    fun pruneCache(context: Context, maxAgeDays: Int = 7, maxBytes: Long = 20L * 1024L * 1024L): Int {
        val dir = File(context.cacheDir, "online_lyrics_cache")
        if (!dir.exists()) return 0
        val files = dir.listFiles()?.filter { it.isFile }?.toMutableList() ?: return 0
        val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
        var removed = 0
        val iter = files.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            if (f.lastModified() < cutoff) {
                if (f.delete()) removed++
                iter.remove()
            }
        }
        var total = files.sumOf { it.length() }
        if (total > maxBytes) {
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (total <= maxBytes) break
                val len = f.length()
                if (f.delete()) {
                    removed++
                    total -= len
                }
            }
        }
        return removed
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

    /**
     * Queries all primary word-synced and line-synced sources concurrently (inspired by Square's
     * LyricsLibrary) and returns immediately as soon as the highest-priority true word-sync answer
     * arrives, falling back to the best synced line result within [PARALLEL_BUDGET_MS].
     */
    private fun fetchParallelAuto(
        rawTitle: String, cleanTitle: String, artist: String, durationMs: Long,
    ): FetchedPayload? {
        val tasks = listOf<Pair<String, () -> FetchedPayload?>>(
            "lyricsplus" to { tryLyricsPlus(rawTitle, cleanTitle, artist, durationMs) },
            "lossless" to { tryLossless(rawTitle, cleanTitle, artist, durationMs) },
            "kugou" to { tryKugou(rawTitle, cleanTitle, artist, durationMs) },
            "better-lyrics" to { tryBetterLyrics(rawTitle, cleanTitle, artist, durationMs) },
            "lrclib" to { tryLrclib(rawTitle, cleanTitle, artist, durationMs) },
        )
        val futures: List<Pair<String, Future<FetchedPayload?>>> = tasks.map { (name, fn) ->
            name to fetchPool.submit(Callable {
                try {
                    fn()
                } catch (_: Throwable) {
                    null
                }
            })
        }
        val deadline = System.currentTimeMillis() + PARALLEL_BUDGET_MS
        val results = arrayOfNulls<FetchedPayload>(futures.size)
        val done = BooleanArray(futures.size)
        try {
            while (true) {
                var allDone = true
                for (i in futures.indices) {
                    if (!done[i] && futures[i].second.isDone) {
                        done[i] = true
                        results[i] = runCatching { futures[i].second.get() }.getOrNull()
                    }
                    if (!done[i]) allDone = false
                }
                // Check if the highest-priority undecided prefix is cleared and we already have a true word-sync hit
                var prefixReady = true
                for (i in futures.indices) {
                    if (!done[i]) {
                        prefixReady = false
                        break
                    }
                    val r = results[i]
                    if (r != null && r.isTrueWordSync) return r
                }
                if (allDone || System.currentTimeMillis() >= deadline) break
                try {
                    Thread.sleep(40L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            futures.forEach { (_, f) -> if (!f.isDone) f.cancel(true) }
        }
        // Pick best true word-sync result first, then best line-synced result by priority order
        results.firstOrNull { it != null && it.isTrueWordSync }?.let { return it }
        results.firstOrNull { it != null }?.let { return it }
        return tryLyricsOvh(rawTitle, cleanTitle, artist, durationMs)
    }

    // 0. Apple Music word-by-word (LyricsPlus v2 primary -> Lossless TTML -> iTunes + Paxsenix fallback)
    private fun tryAppleMusic(rawTitle: String, cleanTitle: String, artist: String, durationMs: Long): FetchedPayload? {
        var lineFallback: FetchedPayload? = null
        tryLyricsPlus(rawTitle, cleanTitle, artist, durationMs)?.let { res ->
            if (res.isTrueWordSync) return res
            lineFallback = res
        }
        tryLossless(rawTitle, cleanTitle, artist, durationMs)?.let { res ->
            if (res.isTrueWordSync) return res
            if (lineFallback == null) lineFallback = res
        }
        val paxResult = try {
            val primaryArtist = primaryArtistName(artist)
            val term = if (primaryArtist.isNotBlank()) "$cleanTitle $primaryArtist" else cleanTitle
            val searchUrl = "https://itunes.apple.com/search?term=${enc(term)}&entity=song&limit=5"
            val searchBody = httpGet(searchUrl)
            val results = searchBody?.let { JSONObject(it).optJSONArray("results") }
            var trackId: Long? = null
            if (results != null) {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optLong("trackId", 0L)
                    if (id <= 0L) continue
                    val tName = item.optString("trackName")
                    val aName = item.optString("artistName")
                    if (trackId == null) trackId = id
                    if (tName.contains(cleanTitle, ignoreCase = true) &&
                        (primaryArtist.isBlank() || aName.contains(primaryArtist, ignoreCase = true))) {
                        trackId = id
                        break
                    }
                }
            }
            if (trackId != null) {
                val lyricBody = httpGet("https://lyrics.paxsenix.org/apple-music/lyrics?id=$trackId")
                if (lyricBody != null) {
                    val obj = JSONObject(lyricBody)
                    val ttml = obj.optString("ttml").takeIf { it.isNotBlank() }
                        ?: obj.optString("ttmlContent").takeIf { it.isNotBlank() }
                    if (ttml != null) {
                        val lrc = ttmlToEnhancedLrc(ttml)
                        if (lrc.isNotBlank()) {
                            val hasWords = lrc.contains(ANGLE_TS_REGEX)
                            val payload = JSONObject()
                                .put("songName", rawTitle)
                                .put("artist", artist)
                                .put("lyric", lrc)
                                .put("ttml", ttml)
                                .toString()
                            Log.i(TAG, "Fetched TTML word-sync lyrics from Apple Music (id=$trackId, word=$hasWords)")
                            return FetchedPayload("apple-music", payload, isTrueWordSync = hasWords)
                        }
                    }
                    val contentArr = obj.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        val elrcFromContent = paxsenixContentToEnhancedLrc(contentArr)
                        if (elrcFromContent.isNotBlank()) {
                            val hasWords = elrcFromContent.contains(ANGLE_TS_REGEX)
                            Log.i(TAG, "Fetched word-sync JSON lyrics from Apple Music (id=$trackId, word=$hasWords)")
                            return FetchedPayload("apple-music", buildLrcPayload(rawTitle, artist, elrcFromContent, 0L), isTrueWordSync = hasWords)
                        }
                    }
                    val elrc = obj.optString("elrc").takeIf { it.isNotBlank() }
                        ?: obj.optString("lrc").takeIf { it.isNotBlank() }
                    if (elrc != null) {
                        val hasWords = elrc.contains(ANGLE_TS_REGEX)
                        Log.i(TAG, "Fetched LRC lyrics from Apple Music (id=$trackId, word=$hasWords)")
                        return FetchedPayload("apple-music", buildLrcPayload(rawTitle, artist, elrc, 0L), isTrueWordSync = hasWords)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Apple Music failed: ${e.message}")
            null
        }
        return paxResult ?: lineFallback
    }

    // 0b. Lossless / binimum.org Apple Music TTML archive (fast, duration-matched, word-sync prioritized)
    private fun tryLossless(rawTitle: String, cleanTitle: String, artist: String, durationMs: Long): FetchedPayload? {
        return try {
            val primaryArtist = primaryArtistName(artist)
            val sb = StringBuilder("https://lyrics-api.binimum.org/?track=${enc(cleanTitle)}")
            if (primaryArtist.isNotBlank()) sb.append("&artist=${enc(primaryArtist)}")
            if (durationMs > 0L) sb.append("&duration=${durationMs / 1000L}")
            val searchBody = httpGetWithHeaders(
                sb.toString(),
                mapOf(
                    "Referer" to "https://lossless.wtf/",
                    "Origin" to "https://lossless.wtf",
                    "Accept" to "application/json",
                ),
            ) ?: return null
            val results = JSONObject(searchBody).optJSONArray("results") ?: return null
            var bestUrl: String? = null
            var bestScore = Long.MAX_VALUE
            var bestIsWord = false
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val url = item.optString("lyricsUrl")
                if (!url.startsWith("http")) continue
                val isWord = item.optString("timing_type").equals("word", ignoreCase = true)
                val itemDurMs = item.optLong("duration", 0L) * 1000L
                val drift = if (durationMs > 0L && itemDurMs > 0L) abs(itemDurMs - durationMs) else 0L
                if (durationMs > 0L && itemDurMs > 0L && drift > 6_000L) continue
                val score = (if (isWord) 0L else 1_000_000L) + drift
                if (score < bestScore) {
                    bestScore = score
                    bestUrl = url
                    bestIsWord = isWord
                }
            }
            val ttmlUrl = bestUrl ?: return null
            val ttml = httpGetWithHeaders(
                ttmlUrl,
                mapOf(
                    "Referer" to "https://lossless.wtf/",
                    "Origin" to "https://lossless.wtf",
                    "Accept" to "application/ttml+xml, application/xml, text/plain",
                ),
            ) ?: return null
            val lrc = ttmlToEnhancedLrc(ttml)
            if (lrc.isBlank()) return null
            val hasWordTags = lrc.contains(Regex("""<\d{1,4}:\d{2}(?:[.:]\d{1,3})?>"""))
            val payload = if (hasWordTags) {
                JSONObject()
                    .put("songName", rawTitle)
                    .put("artist", artist)
                    .put("lyric", lrc)
                    .put("ttml", ttml)
                    .toString()
            } else {
                buildLrcPayload(rawTitle, artist, lrc, durationMs)
            }
            Log.i(TAG, "Fetched TTML from Lossless (word=${bestIsWord || hasWordTags}, len=${lrc.length})")
            FetchedPayload("apple-music", payload, isTrueWordSync = bestIsWord || hasWordTags)
        } catch (e: Exception) {
            Log.w(TAG, "Lossless failed: ${e.message}")
            null
        }
    }

    // 1. Better Lyrics (Apple Music TTML word-by-word)
    private fun tryBetterLyrics(rawTitle: String, cleanTitle: String, artist: String, durationMs: Long): FetchedPayload? {
        return try {
            val primaryArtist = primaryArtistName(artist)
            val candidates = listOf(artist, primaryArtist).distinct().filter { it.isNotBlank() } + listOf("")
            for (candArtist in candidates) {
                val sb = StringBuilder("https://lyrics-api.boidu.dev/getLyrics?s=${enc(cleanTitle)}&a=${enc(candArtist)}")
                if (durationMs > 0L) sb.append("&d=${durationMs / 1000L}")
                val body = httpGet(sb.toString()) ?: continue
                val json = JSONObject(body)
                val ttml = json.optString("ttml").takeIf { it.isNotBlank() } ?: continue
                val lrc = ttmlToEnhancedLrc(ttml)
                if (lrc.isBlank()) continue
                val hasWordTags = lrc.contains(Regex("""<\d{1,4}:\d{2}(?:[.:]\d{1,3})?>"""))
                val payload = JSONObject()
                    .put("songName", rawTitle)
                    .put("artist", artist)
                    .put("lyric", lrc)
                    .put("ttml", ttml)
                    .toString()
                Log.i(TAG, "Fetched TTML word-sync lyrics from Better Lyrics (${lrc.length} chars)")
                return FetchedPayload("better-lyrics", payload, isTrueWordSync = hasWordTags)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Better Lyrics failed: ${e.message}")
            null
        }
    }

    // 2. LRCLIB (synced LRC)
    private fun tryLrclib(rawTitle: String, cleanTitle: String, artist: String, durationMs: Long): FetchedPayload? {
        try {
            val sb = StringBuilder("https://lrclib.net/api/get?track_name=${enc(cleanTitle)}")
            if (artist.isNotBlank()) sb.append("&artist_name=${enc(artist)}")
            if (durationMs > 0L) sb.append("&duration=${durationMs / 1000}")
            httpGet(sb.toString())?.let { body ->
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (synced != null) {
                    val payload = buildLrcPayload(rawTitle, artist, synced, durationMs)
                    Log.i(TAG, "Fetched synced lyrics from LRCLIB /get")
                    return FetchedPayload("lrclib", payload)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB /get failed: ${e.message}")
        }

        return try {
            val q = if (artist.isNotBlank()) "$cleanTitle $artist" else cleanTitle
            val body = httpGet("https://lrclib.net/api/search?q=${enc(q)}") ?: return null
            val arr = JSONArray(body)
            var bestSynced: String? = null
            var bestSyncedDrift = Long.MAX_VALUE
            var bestPlain: String? = null
            for (i in 0 until minOf(arr.length(), 10)) {
                val item = arr.optJSONObject(i) ?: continue
                val itemDurMs = (item.optDouble("duration", 0.0) * 1000.0).toLong()
                val drift = if (durationMs > 0L && itemDurMs > 0L) abs(itemDurMs - durationMs) else 0L
                val synced = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (synced != null) {
                    if (durationMs <= 0L || itemDurMs <= 0L || drift <= 6_000L) {
                        if (drift < bestSyncedDrift) {
                            bestSyncedDrift = drift
                            bestSynced = synced
                        }
                    } else if (bestSynced == null) {
                        bestSynced = synced
                    }
                }
                if (bestPlain == null) {
                    bestPlain = item.optString("plainLyrics").takeIf { it.isNotBlank() }
                }
            }
            val lrc = bestSynced ?: bestPlain?.let { plainToSyntheticLrc(it, durationMs) } ?: return null
            Log.i(TAG, "Fetched lyrics from LRCLIB /search (synced=${bestSynced != null}, drift=${bestSyncedDrift}ms)")
            FetchedPayload("lrclib", buildLrcPayload(rawTitle, artist, lrc, durationMs))
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB /search failed: ${e.message}")
            null
        }
    }

    // 3. Kugou (prefers decrypted KRC true word-by-word, falls back to LRC)
    private fun tryKugou(rawTitle: String, cleanTitle: String, artist: String, durationMs: Long): FetchedPayload? {
        return try {
            val primaryArtist = primaryArtistName(artist)
            val kw = if (primaryArtist.isNotBlank()) "$primaryArtist - $cleanTitle" else cleanTitle
            val dur = if (durationMs > 0L) durationMs else 240_000L
            val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(kw)}&duration=$dur"
            val searchBody = httpGet(searchUrl) ?: return null
            val candidates = JSONObject(searchBody).optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            var best: JSONObject? = null
            var minDrift = Long.MAX_VALUE
            for (i in 0 until candidates.length()) {
                val c = candidates.optJSONObject(i) ?: continue
                if (c.optString("id").isBlank() || c.optString("accesskey").isBlank()) continue
                val cDur = c.optLong("duration", 0L)
                val drift = if (durationMs > 0L && cDur > 0L) abs(cDur - durationMs) else 0L
                if (durationMs > 0L && cDur > 0L && drift > 4_000L) continue
                if (drift < minDrift) {
                    minDrift = drift
                    best = c
                }
            }
            val chosen = best ?: return null
            val id = chosen.optString("id")
            val accesskey = chosen.optString("accesskey")

            // Try KRC (true word-by-word) first
            val krcUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(accesskey)}&fmt=krc&charset=utf8"
            httpGet(krcUrl)?.let { dlBody ->
                val contentB64 = JSONObject(dlBody).optString("content")
                if (contentB64.isNotBlank()) {
                    val decrypted = decryptKrc(Base64.decode(contentB64, Base64.DEFAULT))
                    val elrc = decrypted?.let { krcToEnhancedLrc(it, cleanTitle) }
                    if (!elrc.isNullOrBlank()) {
                        Log.i(TAG, "Fetched KRC word-sync lyrics from Kugou (${elrc.length} chars)")
                        return FetchedPayload("kugou-krc", buildLrcPayload(rawTitle, artist, elrc, durationMs), isTrueWordSync = true)
                    }
                }
            }

            // Fallback to plain LRC
            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(accesskey)}&fmt=lrc&charset=utf8"
            val dlBody = httpGet(dlUrl) ?: return null
            val contentB64 = JSONObject(dlBody).optString("content")
            if (contentB64.isBlank()) return null
            val lrc = String(Base64.decode(contentB64, Base64.DEFAULT), StandardCharsets.UTF_8)
                .removePrefix("\uFEFF")
            if (lrc.isBlank()) return null
            Log.i(TAG, "Fetched LRC lyrics from Kugou (${lrc.length} chars)")
            FetchedPayload("kugou", buildLrcPayload(rawTitle, artist, lrc, durationMs))
        } catch (e: Exception) {
            Log.w(TAG, "Kugou failed: ${e.message}")
            null
        }
    }

    private fun decryptKrc(raw: ByteArray): String? {
        if (raw.size <= 4 || String(raw, 0, 4, StandardCharsets.US_ASCII) != "krc1") return null
        val body = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor KRC_KEY[i % KRC_KEY.size]).toByte() }
        val inflater = Inflater()
        return try {
            inflater.setInput(body)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun krcToEnhancedLrc(krc: String, cleanTitle: String): String {
        val lineRe = Regex("""^\[(\d+),(\d+)]""")
        val wordRe = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
        val out = StringBuilder()
        var started = false
        for (rawLine in krc.lineSequence()) {
            val head = lineRe.find(rawLine) ?: continue
            val lineStart = head.groupValues[1].toLongOrNull() ?: continue
            val lineDur = head.groupValues[2].toLongOrNull() ?: 0L
            val pieces = wordRe.findAll(rawLine.substring(head.range.last + 1)).toList()
            if (pieces.isEmpty()) continue
            val fullText = pieces.joinToString("") { it.groupValues[3] }.trim()
            if (fullText.isEmpty()) continue
            if (!started) {
                val isCredit = fullText.contains('：') || fullText.contains(':') ||
                    (fullText.startsWith(cleanTitle, ignoreCase = true) && fullText.contains(" - "))
                if (isCredit) continue
                started = true
            }
            val row = StringBuilder()
            var lastEnd = lineStart
            for (j in pieces.indices) {
                val m = pieces[j]
                val off = m.groupValues[1].toLongOrNull() ?: continue
                val dur = m.groupValues[2].toLongOrNull() ?: 0L
                val tok = m.groupValues[3]
                if (tok.isEmpty()) continue
                val wStart = lineStart + off
                val wEnd = wStart + dur
                row.append(formatAngleTime(wStart)).append(tok)
                val nextOff = pieces.getOrNull(j + 1)?.groupValues?.get(1)?.toLongOrNull()
                if (nextOff != null && wEnd + 60L < lineStart + nextOff) {
                    row.append(formatAngleTime(wEnd))
                }
                if (wEnd > lastEnd) lastEnd = wEnd
            }
            if (row.isNotEmpty()) {
                val finalEnd = maxOf(lineStart + lineDur, lastEnd)
                out.append(formatLrcTime(lineStart)).append(row)
                if (finalEnd > lineStart) out.append(formatAngleTime(finalEnd))
                out.append('\n')
            }
        }
        return out.toString()
    }

    // 4. lyrics.ovh (plain text fallback)
    private fun tryLyricsOvh(rawTitle: String, cleanTitle: String, artist: String, durationMs: Long): FetchedPayload? {
        if (artist.isBlank()) return null
        return try {
            val url = "https://api.lyrics.ovh/v1/${encPath(artist)}/${encPath(cleanTitle)}"
            val body = httpGet(url) ?: return null
            val plain = JSONObject(body).optString("lyrics").takeIf { it.isNotBlank() } ?: return null
            val lrc = plainToSyntheticLrc(plain, durationMs)
            if (lrc.isBlank()) return null
            Log.i(TAG, "Fetched plain lyrics from lyrics.ovh")
            FetchedPayload("lyrics.ovh", buildLrcPayload(rawTitle, artist, lrc, durationMs))
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

    private fun tryLyricsPlus(rawTitle: String, cleanTitle: String, artist: String, durationMs: Long): FetchedPayload? {
        val primaryArtist = primaryArtistName(artist)
        val durSec = (durationMs / 1000L).takeIf { it > 0L }
        val attempts = buildList {
            if (artist.isNotBlank()) add(artist to durSec)
            if (primaryArtist.isNotBlank() && (primaryArtist != artist || durSec != null)) add(primaryArtist to null)
            if (artist.isBlank()) add("" to durSec)
        }.distinct()

        for ((candArtist, candDur) in attempts) {
            try {
                val sb = StringBuilder("https://lyricsplus.prjktla.my.id/v2/lyrics/get?title=${enc(cleanTitle)}")
                if (candArtist.isNotBlank()) sb.append("&artist=${enc(candArtist)}")
                if (candDur != null) sb.append("&duration=$candDur")
                sb.append("&source=apple,lyricsplus,musixmatch-word,spotify")
                val body = httpGet(sb.toString()) ?: continue
                val json = JSONObject(body)
                val lyricsArr = json.optJSONArray("lyrics") ?: continue
                if (lyricsArr.length() == 0) continue
                val elrc = lyricsPlusV2ToEnhancedLrc(lyricsArr)
                if (elrc.isBlank()) continue
                val upstreamSrc = json.optJSONObject("metadata")?.optString("source").orEmpty().lowercase(Locale.ROOT)
                val sourceTag = when {
                    upstreamSrc.contains("apple") -> "apple-music"
                    upstreamSrc.isNotBlank() -> "lyricsplus-$upstreamSrc"
                    else -> "lyricsplus"
                }
                val payload = buildLrcPayload(rawTitle, artist, elrc, durationMs)
                val isWord = json.optString("type").equals("Word", ignoreCase = true) ||
                    elrc.contains(Regex("""<\d{1,4}:\d{2}(?:[.:]\d{1,3})?>"""))
                Log.i(TAG, "Fetched word-sync lyrics from LyricsPlus ($sourceTag, word=$isWord, lines=${lyricsArr.length()})")
                return FetchedPayload(sourceTag, payload, isTrueWordSync = isWord)
            } catch (e: Exception) {
                Log.w(TAG, "LyricsPlus failed ($candArtist): ${e.message}")
            }
        }
        return null
    }

    private fun lyricsPlusV2ToEnhancedLrc(lyrics: JSONArray): String {
        val out = StringBuilder()
        for (i in 0 until lyrics.length()) {
            val lineObj = lyrics.optJSONObject(i) ?: continue
            val lineStart = lineObj.optLong("time", -1L)
            if (lineStart < 0L) continue
            val lineDur = lineObj.optLong("duration", 0L).coerceAtLeast(0L)
            val lineEnd = lineStart + lineDur
            val syllabus = lineObj.optJSONArray("syllabus")
            if (syllabus != null && syllabus.length() > 0) {
                data class Syl(val start: Long, val end: Long, val text: String)
                val items = ArrayList<Syl>(syllabus.length())
                for (j in 0 until syllabus.length()) {
                    val s = syllabus.optJSONObject(j) ?: continue
                    if (s.optBoolean("isBackground", false)) continue
                    val wText = s.optString("text")
                    if (wText.isEmpty()) continue
                    val wStart = s.optLong("time", -1L)
                    if (wStart < 0L) continue
                    val wDur = s.optLong("duration", 0L).coerceAtLeast(0L)
                    items += Syl(wStart, wStart + wDur, wText)
                }
                if (items.isNotEmpty()) {
                    val rowStart = minOf(lineStart, items.first().start)
                    val row = StringBuilder()
                    var lastEnd = rowStart
                    for (j in items.indices) {
                        val cur = items[j]
                        row.append(formatAngleTime(cur.start)).append(cur.text)
                        val nextStart = items.getOrNull(j + 1)?.start
                        if (cur.end > cur.start && nextStart != null && cur.end + 60L < nextStart) {
                            row.append(formatAngleTime(cur.end))
                        }
                        if (cur.end > lastEnd) lastEnd = cur.end
                    }
                    val finalEnd = maxOf(lineEnd, lastEnd)
                    out.append(formatLrcTime(rowStart)).append(row)
                    if (finalEnd > rowStart) out.append(formatAngleTime(finalEnd))
                    out.append('\n')
                    continue
                }
            }
            val plain = lineObj.optString("text").trim()
            if (plain.isNotEmpty()) {
                out.append(formatLrcTime(lineStart)).append(plain).append('\n')
            }
        }
        return out.toString()
    }

    private fun paxsenixContentToEnhancedLrc(content: JSONArray): String {
        val out = StringBuilder()
        for (i in 0 until content.length()) {
            val lineObj = content.optJSONObject(i) ?: continue
            val lineStart = lineObj.optLong("timestamp", -1L)
            if (lineStart < 0L) continue
            val lineEnd = lineObj.optLong("endtime", -1L)
            val syllables = lineObj.optJSONArray("text")
            if (syllables != null && syllables.length() > 0) {
                val row = StringBuilder()
                var lastEnd = -1L
                for (j in 0 until syllables.length()) {
                    val syl = syllables.optJSONObject(j) ?: continue
                    val wordText = syl.optString("text")
                    if (wordText.isEmpty()) continue
                    val ts = syl.optLong("timestamp", lineStart)
                    val end = syl.optLong("endtime", -1L)
                    val part = syl.optBoolean("part", false)
                    row.append(formatAngleTime(ts)).append(wordText)
                    if (!part && j < syllables.length() - 1 && !wordText.endsWith(" ")) {
                        row.append(' ')
                    }
                    if (end > lastEnd) lastEnd = end
                }
                if (row.isNotEmpty()) {
                    val finalEnd = if (lineEnd > lineStart) lineEnd else lastEnd
                    out.append(formatLrcTime(lineStart)).append(row)
                    if (finalEnd > lineStart) out.append(formatAngleTime(finalEnd))
                    out.append('\n')
                }
            } else {
                val plain = lineObj.optString("text").trim()
                if (plain.isNotEmpty()) {
                    out.append(formatLrcTime(lineStart)).append(plain).append('\n')
                }
            }
        }
        return out.toString()
    }

    private fun ttmlToEnhancedLrc(ttml: String): String {
        val pRegex = Regex(
            """<p\b([^>]*)>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val beginAttr = Regex("""\bbegin="([^"]+)"""", RegexOption.IGNORE_CASE)
        val endAttr = Regex("""\bend="([^"]+)"""", RegexOption.IGNORE_CASE)
        val auxSpanRegex = Regex(
            """<span\b[^>]*\b(?:[a-zA-Z0-9_-]+:)?role="(?:x-bg|x-translation|x-roman|translation|romanization|background)"[^>]*>.*?</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val spanRegex = Regex(
            """<span\b([^>]*)>(.*?)</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val tagRegex = Regex("""<[^>]+>""")
        val out = StringBuilder()

        for (match in pRegex.findAll(ttml)) {
            val pAttrs = match.groupValues[1]
            val pStart = beginAttr.find(pAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs) ?: continue
            val pEnd = endAttr.find(pAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs)
            val inner = auxSpanRegex.replace(match.groupValues[2], "")

            // Extract word-level <span begin="..." end="..."> directly into Enhanced LRC
            val spanMatches = spanRegex.findAll(inner).toList()
            val row = StringBuilder()
            var wordCount = 0
            var lastWordEnd = pStart
            if (spanMatches.isNotEmpty()) {
                var cursor = 0
                for (sm in spanMatches) {
                    val gap = decodeXmlEntities(tagRegex.replace(inner.substring(cursor, sm.range.first), ""))
                    if (gap.isNotEmpty() && wordCount > 0) {
                        row.append(if (gap.all { it.isWhitespace() }) " " else gap)
                    }
                    cursor = sm.range.last + 1
                    val sAttrs = sm.groupValues[1]
                    val sStart = beginAttr.find(sAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs)
                    val sEnd = endAttr.find(sAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs)
                    val sText = decodeXmlEntities(tagRegex.replace(sm.groupValues[2], ""))
                    if (sText.isNotEmpty()) {
                        if (sStart != null) {
                            val clampedStart = maxOf(pStart, sStart)
                            row.append(formatAngleTime(clampedStart)).append(sText)
                            wordCount++
                            if (sEnd != null && sEnd > lastWordEnd) lastWordEnd = sEnd
                        } else {
                            row.append(sText)
                        }
                    }
                }
                val tail = decodeXmlEntities(tagRegex.replace(inner.substring(cursor), "")).trimEnd()
                if (tail.isNotEmpty()) row.append(tail)
            }

            if (wordCount > 0) {
                val finalEnd = maxOf(pEnd ?: 0L, lastWordEnd)
                out.append(formatLrcTime(pStart)).append(row.toString().trim())
                if (finalEnd > pStart) out.append(formatAngleTime(finalEnd))
                out.append('\n')
            } else {
                val text = decodeXmlEntities(tagRegex.replace(inner, "")).replace(Regex("\\s+"), " ").trim()
                if (text.isEmpty()) continue
                out.append(formatLrcTime(pStart)).append(text).append('\n')
            }
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
        val millis = safe % 1000L
        return String.format(Locale.US, "[%02d:%02d.%03d]", min, sec, millis)
    }

    private fun formatAngleTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val min = safe / 60_000L
        val sec = (safe % 60_000L) / 1000L
        val millis = safe % 1000L
        return String.format(Locale.US, "<%02d:%02d.%03d>", min, sec, millis)
    }

    private fun decodeXmlEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")

    fun applyInterpolationMode(payloadJson: String, isTrueWordSync: Boolean, interpolateWords: Boolean): String {
        if (isTrueWordSync) return payloadJson
        return runCatching {
            val obj = JSONObject(payloadJson)
            val lrc = obj.optString("lyric")
            if (lrc.isBlank()) return payloadJson
            val plain = ANGLE_TS_REGEX.replace(lrc, "")
            val updated = if (interpolateWords) {
                synthesizeWordTimingLrc(plain, 0L, syllableWeighted = true)
            } else {
                synthesizeWordTimingLrc(plain, 0L, syllableWeighted = false)
            }
            if (updated == lrc) payloadJson else obj.put("lyric", updated).toString()
        }.getOrDefault(payloadJson)
    }

    private val ANGLE_TS_REGEX = Regex("""<\d{1,4}:\d{2}(?:[.:]\d{1,3})?>""")

    private fun buildLrcPayload(title: String, artist: String, lrc: String, durationMs: Long): String {
        val enhanced = if (lrc.contains(ANGLE_TS_REGEX)) lrc
            else synthesizeWordTimingLrc(lrc, durationMs, syllableWeighted = false)
        return JSONObject()
            .put("songName", title)
            .put("artist", artist)
            .put("lyric", enhanced)
            .toString()
    }

    /** Estimates per-word timestamps: uniform by character length when [syllableWeighted] is false, or syllable/punctuation weighted when true. */
    private fun synthesizeWordTimingLrc(lrc: String, durationMs: Long, syllableWeighted: Boolean): String {
        val lineTagRegex = Regex("""^((?:\[\d{1,4}:\d{2}(?:[.:]\d{1,3})?])+)(.*)$""")
        val singleTagRegex = Regex("""\[(\d{1,4}):(\d{2})(?:[.:](\d{1,3}))?]""")
        data class Entry(val startMs: Long, val text: String)
        val entries = ArrayList<Entry>()
        for (rawLine in lrc.lineSequence()) {
            val trimmed = rawLine.trim()
            val m = lineTagRegex.matchEntire(trimmed) ?: continue
            val text = m.groupValues[2].trim()
            for (tag in singleTagRegex.findAll(m.groupValues[1])) {
                val min = tag.groupValues[1].toLongOrNull() ?: continue
                val sec = tag.groupValues[2].toLongOrNull() ?: continue
                if (sec >= 60L) continue
                val ms = tag.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                entries += Entry((min * 60L + sec) * 1000L + ms, text)
            }
        }
        if (entries.isEmpty()) return lrc
        entries.sortBy { it.startMs }
        val wordTokenRegex = Regex("""\S+\s*""")

        // Estimate the track's own median singing pace (ms/syllable) for adaptive interpolation.
        val medianMsPerSyl = if (syllableWeighted) {
            val paceSamples = ArrayList<Double>()
            for (i in entries.indices) {
                val e = entries[i]
                if (e.text.isEmpty()) continue
                val nextStart = entries.getOrNull(i + 1)?.startMs ?: continue
                val rawSpan = nextStart - e.startMs
                if (rawSpan !in 400L..9_000L) continue
                val toks = tokenizeLine(e.text, wordTokenRegex)
                val syls = toks.sumOf(::tokenSyllables)
                if (syls >= 3.0) paceSamples += rawSpan.toDouble() / syls
            }
            if (paceSamples.size >= 4) {
                paceSamples.sort()
                val mid = paceSamples.size / 2
                val med = if (paceSamples.size % 2 == 1) paceSamples[mid]
                else (paceSamples[mid - 1] + paceSamples[mid]) * 0.5
                med.coerceIn(130.0, 520.0)
            } else 280.0
        } else 280.0

        val out = StringBuilder()
        for (i in entries.indices) {
            val e = entries[i]
            if (e.text.isEmpty()) {
                out.append(formatLrcTime(e.startMs)).append('\n')
                continue
            }
            val nextStart = entries.getOrNull(i + 1)?.startMs
            val fallbackDur = (e.text.length * 140L).coerceIn(1_800L, 6_500L)
            val rawSpan = when {
                nextStart != null && nextStart > e.startMs -> nextStart - e.startMs
                durationMs > e.startMs -> (durationMs - e.startMs).coerceAtMost(fallbackDur)
                else -> fallbackDur
            }
            val tokens = tokenizeLine(e.text, wordTokenRegex)
            val span = if (syllableWeighted) {
                val syls = tokens.sumOf(::tokenSyllables).coerceAtLeast(1.0)
                val estSingSpan = (syls * medianMsPerSyl * 1.25).toLong()
                minOf((rawSpan * 0.80).toLong(), estSingSpan).coerceAtLeast(350L)
            } else {
                val maxReasonable = (e.text.length * 220L).coerceIn(2_500L, 8_500L)
                rawSpan.coerceAtMost(maxReasonable).coerceAtLeast(350L)
            }
            if (tokens.size <= 1) {
                out.append(formatLrcTime(e.startMs))
                    .append(formatAngleTime(e.startMs)).append(e.text)
                    .append(formatAngleTime(e.startMs + span)).append('\n')
                continue
            }
            if (!syllableWeighted) {
                val totalWeight = tokens.sumOf { it.trim().length.coerceAtLeast(1) }.toDouble()
                var acc = 0.0
                out.append(formatLrcTime(e.startMs))
                for (tok in tokens) {
                    val wStart = e.startMs + (span * (acc / totalWeight)).toLong()
                    out.append(formatAngleTime(wStart)).append(tok)
                    acc += tok.trim().length.coerceAtLeast(1)
                }
                out.append(formatAngleTime(e.startMs + span)).append('\n')
                continue
            }
            val speechWeights = DoubleArray(tokens.size) { idx ->
                val charW = tokens[idx].trim().length.coerceAtLeast(1).toDouble()
                val sylW = tokenSyllables(tokens[idx])
                sylW * 0.75 + (charW * 0.35) * 0.25
            }
            val pauseWeights = DoubleArray(tokens.size) { idx ->
                if (idx == tokens.lastIndex) 0.0 else trailingPauseWeight(tokens[idx])
            }
            var totalWeight = 0.0
            for (idx in tokens.indices) totalWeight += speechWeights[idx] + pauseWeights[idx]
            if (totalWeight <= 0.0) totalWeight = tokens.size.toDouble()

            var acc = 0.0
            out.append(formatLrcTime(e.startMs))
            for (idx in tokens.indices) {
                val wStart = e.startMs + (span * (acc / totalWeight)).toLong()
                out.append(formatAngleTime(wStart)).append(tokens[idx])
                acc += speechWeights[idx]
                if (pauseWeights[idx] > 0.0) {
                    val wEnd = e.startMs + (span * (acc / totalWeight)).toLong()
                    if (wEnd > wStart + 40L) out.append(formatAngleTime(wEnd))
                    acc += pauseWeights[idx]
                }
            }
            out.append(formatAngleTime(e.startMs + span)).append('\n')
        }
        return out.toString()
    }

    private fun tokenizeLine(text: String, wordTokenRegex: Regex): List<String> {
        val words = wordTokenRegex.findAll(text).map { it.value }.toList()
        return if (words.size == 1 && text.length >= 3 && text.any { it.code > 0x2E80 }) {
            text.map { it.toString() }
        } else words
    }

    private fun tokenSyllables(token: String): Double {
        val core = token.trim().trimEnd(',', '.', '!', '?', ';', ':', '、', '，', '。', '！', '？', '—', '…', '"', '\'', ')')
        if (core.isEmpty()) return 0.4
        if (core.any { it.code > 0x2E80 }) {
            return core.length.toDouble().coerceAtLeast(1.0)
        }
        val lower = core.lowercase(Locale.ROOT)
        var vowelGroups = 0
        var prevVowel = false
        for (ch in lower) {
            val isVowel = ch in "aeiouyăâêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ"
            if (isVowel && !prevVowel) vowelGroups++
            prevVowel = isVowel
        }
        return vowelGroups.coerceAtLeast(1).toDouble()
    }

    private fun trailingPauseWeight(token: String): Double {
        val t = token.trim()
        if (t.isEmpty()) return 0.0
        return when (t.last()) {
            ',', '、', '，', ';' -> 0.15
            '.', '!', '?', '。', '！', '？', '—', '…' -> 0.27
            else -> 0.0
        }
    }

    private fun primaryArtistName(artist: String): String {
        val trimmed = artist.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.split(Regex("""\s*(?:,|&|feat\.|ft\.| x |×|/)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim().orEmpty()
    }

    private fun looksVietnamese(lines: List<String>): Boolean {
        val sample = lines.take(12).joinToString(" ")
        val viChars = "ăâđêôơưĂÂĐÊÔƠƯáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ"
        val hits = sample.count { it in viChars }
        return hits >= 4
    }

    private fun httpGet(urlStr: String): String? = httpGetWithHeaders(urlStr, emptyMap())

    private fun httpGetWithHeaders(urlStr: String, extraHeaders: Map<String, String>): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
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
