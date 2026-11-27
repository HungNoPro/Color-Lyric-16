package shop.terramoor.github.io.lyrics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class LyricBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val input = extras ?: return null
        return when (method) {
            METHOD_FETCH_LYRICS -> {
                if (input.getBoolean(EXTRA_AUTO_CLEAR_CACHE, false)) {
                    maybeAutoPruneCache(ctx)
                }
                val title = input.getString(EXTRA_TITLE).orEmpty()
                val artist = input.getString(EXTRA_ARTIST)
                val durationMs = input.getLong(EXTRA_DURATION_MS, 0L)
                val sourceMode = input.getString(EXTRA_SOURCE_MODE) ?: "AUTO"
                val interpolateWords = input.getBoolean(EXTRA_INTERPOLATE_WORDS, false)
                val forceRefresh = input.getBoolean(EXTRA_FORCE_REFRESH, false)
                val fetched = OnlineLyricFetcher.fetchLyrics(ctx, title, artist, durationMs, sourceMode, forceRefresh) ?: return Bundle()
                val adjustedPayload = OnlineLyricFetcher.applyInterpolationMode(
                    fetched.payloadJson,
                    fetched.isTrueWordSync,
                    interpolateWords,
                )
                Bundle().apply {
                    putString(EXTRA_SOURCE, fetched.source)
                    putBoolean(EXTRA_WORD_TIMED, fetched.isTrueWordSync)
                    putString(EXTRA_PAYLOAD, adjustedPayload)
                }
            }
            METHOD_TRANSLATE_LINES -> {
                val lines = input.getStringArrayList(EXTRA_LINES) ?: return Bundle()
                val targetLang = input.getString(EXTRA_TARGET_LANG) ?: "en"
                val translated = OnlineLyricFetcher.translateLines(ctx, lines, targetLang) ?: return Bundle()
                Bundle().apply {
                    putStringArrayList(EXTRA_TRANSLATIONS, ArrayList(translated))
                }
            }
            METHOD_REPORT_HOOK_STATUS -> {
                val prefs = ctx.getSharedPreferences(HOOK_STATUS_PREFS, android.content.Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(KEY_FLUID_CLOUD, input.getBoolean(KEY_FLUID_CLOUD, false))
                    .putBoolean(KEY_LOCKSCREEN, input.getBoolean(KEY_LOCKSCREEN, false))
                    .putBoolean(KEY_AOD, input.getBoolean(KEY_AOD, false))
                    .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                    .apply()
                Bundle()
            }
            METHOD_REPORT_NOW_PLAYING -> {
                val prefs = ctx.getSharedPreferences(NOW_PLAYING_PREFS, android.content.Context.MODE_PRIVATE)
                val prevTitle = prefs.getString(KEY_NP_TITLE, "").orEmpty()
                val prevArtist = prefs.getString(KEY_NP_ARTIST, "").orEmpty()
                val title = input.getString(EXTRA_TITLE).orEmpty()
                val artist = input.getString(EXTRA_ARTIST).orEmpty()
                val source = input.getString(EXTRA_SOURCE).orEmpty()
                val wordTimed = input.getBoolean(EXTRA_WORD_TIMED, false)
                val coverBytes = input.getByteArray(EXTRA_COVER_BYTES)
                val lyricsLinesJson = input.getString(EXTRA_LYRICS_LINES_JSON)
                val coverFile = java.io.File(ctx.cacheDir, NOW_PLAYING_COVER_FILE)
                val lyricsFile = java.io.File(ctx.cacheDir, NOW_PLAYING_LYRICS_FILE)
                val trackChanged = title != prevTitle || artist != prevArtist
                var coverRev = prefs.getLong(KEY_NP_COVER_REV, 0L)
                var lyricsRev = prefs.getLong(KEY_NP_LYRICS_REV, 0L)
                runCatching {
                    if (coverBytes != null && coverBytes.isNotEmpty()) {
                        coverFile.writeBytes(coverBytes)
                        coverRev = System.currentTimeMillis()
                    } else if (title.isBlank() || trackChanged) {
                        coverFile.delete()
                        coverRev = 0L
                    }
                }
                runCatching {
                    if (lyricsLinesJson != null) {
                        if (lyricsLinesJson.isNotEmpty()) {
                            lyricsFile.writeText(lyricsLinesJson, Charsets.UTF_8)
                            lyricsRev = System.currentTimeMillis()
                        } else {
                            lyricsFile.delete()
                            lyricsRev = 0L
                        }
                    } else if (title.isBlank() || trackChanged || source.isBlank()) {
                        lyricsFile.delete()
                        lyricsRev = 0L
                    }
                }
                val savedOffsetMs = if (title.isNotBlank()) readTrackOffset(ctx, title, artist) else 0L
                prefs.edit()
                    .putString(KEY_NP_TITLE, title)
                    .putString(KEY_NP_ARTIST, artist)
                    .putString(KEY_NP_SOURCE, source)
                    .putBoolean(KEY_NP_WORD_TIMED, wordTimed)
                    .putLong(KEY_NP_COVER_REV, coverRev)
                    .putLong(KEY_NP_LYRICS_REV, lyricsRev)
                    .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                    .apply()
                Bundle().apply {
                    putLong(EXTRA_OFFSET_MS, savedOffsetMs)
                }
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".LyricBridge"
        const val METHOD_FETCH_LYRICS = "fetch_lyrics"
        const val METHOD_TRANSLATE_LINES = "translate_lines"
        const val METHOD_REPORT_HOOK_STATUS = "report_hook_status"
        const val METHOD_REPORT_NOW_PLAYING = "report_now_playing"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_SOURCE_MODE = "source_mode"
        const val EXTRA_INTERPOLATE_WORDS = "interpolate_words"
        const val EXTRA_AUTO_CLEAR_CACHE = "auto_clear_cache"
        const val EXTRA_FORCE_REFRESH = "force_refresh"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_WORD_TIMED = "word_timed"
        const val EXTRA_COVER_BYTES = "cover_bytes"
        const val EXTRA_LYRICS_LINES_JSON = "lyrics_lines_json"
        const val EXTRA_OFFSET_MS = "offset_ms"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_LINES = "lines"
        const val EXTRA_TARGET_LANG = "target_lang"
        const val EXTRA_TRANSLATIONS = "translations"
        const val HOOK_STATUS_PREFS = "hook_status"
        const val NOW_PLAYING_PREFS = "now_playing"
        const val LYRIC_OFFSETS_PREFS = "lyric_offsets"
        const val NOW_PLAYING_COVER_FILE = "now_playing_cover.jpg"
        const val NOW_PLAYING_LYRICS_FILE = "now_playing_lyrics.json"
        const val KEY_FLUID_CLOUD = "fluid_cloud_hooked"
        const val KEY_LOCKSCREEN = "lockscreen_hooked"
        const val KEY_AOD = "aod_hooked"
        const val KEY_NP_TITLE = "np_title"
        const val KEY_NP_ARTIST = "np_artist"
        const val KEY_NP_SOURCE = "np_source"
        const val KEY_NP_WORD_TIMED = "np_word_timed"
        const val KEY_NP_COVER_REV = "np_cover_rev"
        const val KEY_NP_LYRICS_REV = "np_lyrics_rev"
        const val KEY_UPDATED_AT = "updated_at"
        const val MIN_OFFSET_MS = -2_000L
        const val MAX_OFFSET_MS = 2_000L
        const val OFFSET_STEP_MS = 200L

        data class NowPlayingLyricLine(
            val startMs: Long,
            val text: String,
            val translation: String? = null,
        )

        data class HookStatus(
            val fluidCloud: Boolean = false,
            val lockscreen: Boolean = false,
            val aod: Boolean = false,
            val updatedAt: Long = 0L,
        )

        data class NowPlaying(
            val title: String = "",
            val artist: String = "",
            val source: String = "",
            val wordTimed: Boolean = false,
            val updatedAt: Long = 0L,
        )

        fun readNowPlaying(context: android.content.Context): NowPlaying {
            val prefs = context.getSharedPreferences(NOW_PLAYING_PREFS, android.content.Context.MODE_PRIVATE)
            return NowPlaying(
                title = prefs.getString(KEY_NP_TITLE, "").orEmpty(),
                artist = prefs.getString(KEY_NP_ARTIST, "").orEmpty(),
                source = prefs.getString(KEY_NP_SOURCE, "").orEmpty(),
                wordTimed = prefs.getBoolean(KEY_NP_WORD_TIMED, false),
                updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            )
        }

        fun readNowPlayingCover(context: android.content.Context): android.graphics.Bitmap? = runCatching {
            val file = java.io.File(context.cacheDir, NOW_PLAYING_COVER_FILE)
            if (file.isFile) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
        }.getOrNull()

        fun readNowPlayingLyrics(context: android.content.Context): List<NowPlayingLyricLine> = runCatching {
            val file = java.io.File(context.cacheDir, NOW_PLAYING_LYRICS_FILE)
            if (!file.isFile) return emptyList()
            val arr = org.json.JSONArray(file.readText(Charsets.UTF_8))
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val text = obj.optString("l").trim()
                    if (text.isEmpty()) continue
                    val tr = obj.optString("tr").trim().takeIf { it.isNotEmpty() }
                    add(NowPlayingLyricLine(obj.optLong("t", 0L), text, tr))
                }
            }
        }.getOrDefault(emptyList())

        private fun offsetKey(title: String, artist: String): String =
            "${title.trim().lowercase(java.util.Locale.ROOT)}|${artist.trim().lowercase(java.util.Locale.ROOT)}"

        fun readTrackOffset(context: android.content.Context, title: String, artist: String): Long {
            if (title.isBlank()) return 0L
            val prefs = context.getSharedPreferences(LYRIC_OFFSETS_PREFS, android.content.Context.MODE_PRIVATE)
            return prefs.getLong(offsetKey(title, artist), 0L).coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        }

        fun saveTrackOffset(context: android.content.Context, title: String, artist: String, offsetMs: Long): Long {
            val clamped = offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
            if (title.isBlank()) return clamped
            val prefs = context.getSharedPreferences(LYRIC_OFFSETS_PREFS, android.content.Context.MODE_PRIVATE)
            val key = offsetKey(title, artist)
            prefs.edit().apply {
                if (clamped == 0L) remove(key) else putLong(key, clamped)
            }.apply()
            return clamped
        }

        @Volatile
        private var lastAutoPruneMs = 0L

        private fun maybeAutoPruneCache(context: android.content.Context) {
            val now = System.currentTimeMillis()
            if (now - lastAutoPruneMs < 6L * 3_600_000L) return
            lastAutoPruneMs = now
            OnlineLyricFetcher.pruneCache(context, maxAgeDays = 7, maxBytes = 20L * 1024L * 1024L)
        }

        fun readHookStatus(context: android.content.Context): HookStatus {
            val prefs = context.getSharedPreferences(HOOK_STATUS_PREFS, android.content.Context.MODE_PRIVATE)
            return HookStatus(
                fluidCloud = prefs.getBoolean(KEY_FLUID_CLOUD, false),
                lockscreen = prefs.getBoolean(KEY_LOCKSCREEN, false),
                aod = prefs.getBoolean(KEY_AOD, false),
                updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            )
        }
    }
}
