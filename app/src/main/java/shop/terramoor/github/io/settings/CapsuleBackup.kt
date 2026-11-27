package shop.terramoor.github.io.settings

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.InputStream

internal object CapsuleBackup {
    private const val FORMAT = "terramoor.settings"
    private const val VERSION = 14
    private const val MAX_BYTES = 16 * 1024
    const val TRANSLATION_HELP_HIDDEN = "translation_help_hidden"

    data class Snapshot(val settings: CapsuleSettings, val translationHelpHidden: Boolean?)

    fun encode(settings: CapsuleSettings, translationHelpHidden: Boolean = false): String {
        val data = JSONObject()
            .put(CapsuleSettings.ENABLED, settings.enabled)
            .put(CapsuleSettings.DOUBLE_LINE, settings.doubleLine)
            .put(CapsuleSettings.SECOND_ROW_CONTENT, settings.secondRowContent.name)
            .put(CapsuleSettings.SWAP_TRANSLATION, settings.swapTranslation)
            .put(CapsuleSettings.WORD_LYRICS, settings.wordLyrics)
            .put(CapsuleSettings.CAMERA_WORD_AVOIDANCE, settings.cameraWordAvoidance)
            .put(CapsuleSettings.INTRO_DOTS, settings.introDots)
            .put(CapsuleSettings.MISSING_TRANSLATION_MODE, settings.missingTranslationMode.name)
            .put(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, settings.fallbackSingleLineExpand)
            .put(CapsuleSettings.ARTWORK, settings.artwork)
            .put(CapsuleSettings.LYRIC_TINT_ENABLED, settings.lyricTintEnabled)
            .put(CapsuleSettings.LYRIC_TINT_MODE, settings.lyricTintMode.name)
            .put(CapsuleSettings.SHOW_COVER, settings.showCover)
            .put(CapsuleSettings.SHOW_WAVEFORM, settings.showWaveform)
            .put(CapsuleSettings.LYRIC_ALIGNMENT, settings.lyricAlignment.name)
            .put(CapsuleSettings.CAMERA_FADE, settings.cameraFade)
            .put(CapsuleSettings.MOTION, settings.motion.name)
            .put(CapsuleSettings.NO_LYRICS, settings.noLyricsMode.name)
            .put(CapsuleSettings.PAUSE_BEHAVIOR, settings.pauseBehavior.name)
            .put(CapsuleSettings.LYRIC_SOURCE_MODE, settings.lyricSourceMode.name)
            .put(CapsuleSettings.INTERPOLATE_WORD_LYRICS, settings.interpolateWordLyrics)
            .put(CapsuleSettings.AUTO_TRANSLATE_VI, settings.autoTranslateVi)
            .put(CapsuleSettings.TRANSLATE_TARGET_LANG, settings.translateTargetLang.name)
            .put(CapsuleSettings.LOCKSCREEN_BRIDGE, settings.lockscreenBridge)
            .put(CapsuleSettings.AOD_UNLOCK_WHITELIST, settings.aodUnlockWhitelist)
            .put(CapsuleSettings.AUTO_CLEAR_CACHE, settings.autoClearCache)
            .put(CapsuleSettings.APP_WHITELIST_ENABLED, settings.appWhitelistEnabled)
            .put(CapsuleSettings.APP_WHITELIST_PACKAGES, CapsuleSettings.encodePackageSet(settings.appWhitelistPackages))
        CapsuleTuning.entries.forEach { data.put(it.key, settings[it]) }
        val appPreferences = JSONObject().put(TRANSLATION_HELP_HIDDEN, translationHelpHidden)
        return JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("settings", data).put("app_preferences", appPreferences).toString(2)
    }

    fun decode(input: InputStream): CapsuleSettings = decodeSnapshot(input).settings

    fun decodeSnapshot(input: InputStream): Snapshot {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = root.opt("version") as? Int
        require(root.opt("format") == FORMAT && version != null && version in 1..VERSION)
        val data = root.getJSONObject("settings")
        fun flag(key: String) = data.get(key) as? Boolean ?: error("Invalid setting")
        val tuning = CapsuleTuning.entries.associateWith { option ->
            if (version == 7 && option == CapsuleTuning.TINT_STRENGTH) {
                return@associateWith CapsuleSettings.migrateTintStrength(
                    data.getInt(CapsuleSettings.LEGACY_FIRST_ROW_TINT), data.getInt(CapsuleSettings.LEGACY_SECOND_ROW_TINT),
                )
            }
            if (version < option.since) return@associateWith option.default
            if (version < 3 && option == CapsuleTuning.ISLAND_WIDTH) return@associateWith 202 // Historical width for backups before v3.
            (data.get(option.key) as? Int)?.takeIf { it in option.range && option.normalize(it) == it }
                ?: error("Invalid tuning")
        }
        val lyrics = if (version < 6) {
            CapsuleSettings(doubleLine = flag(CapsuleSettings.LEGACY_TRANSLATION), wordLyrics = false, introDots = false)
        } else CapsuleSettings(
            doubleLine = flag(CapsuleSettings.DOUBLE_LINE),
            secondRowContent = SecondRowContent.valueOf(data.getString(CapsuleSettings.SECOND_ROW_CONTENT)),
            swapTranslation = if (version >= 12) flag(CapsuleSettings.SWAP_TRANSLATION) else false,
            wordLyrics = version >= 13 && flag(CapsuleSettings.WORD_LYRICS),
            cameraWordAvoidance = version >= 13 && flag(CapsuleSettings.CAMERA_WORD_AVOIDANCE),
            introDots = version >= 13 && flag(CapsuleSettings.INTRO_DOTS),
            missingTranslationMode = MissingTranslationMode.valueOf(data.getString(CapsuleSettings.MISSING_TRANSLATION_MODE)),
            fallbackSingleLineExpand = flag(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND),
        )
        val storedTintMode = if (version >= 7) data.getString(CapsuleSettings.LYRIC_TINT_MODE)
            else CapsuleSettings.LEGACY_WHITE_TINT
        val legacyTintDisabled = version < 9 && storedTintMode == CapsuleSettings.LEGACY_WHITE_TINT
        val settings = lyrics.copy(
            enabled = flag(CapsuleSettings.ENABLED),
            artwork = flag(CapsuleSettings.ARTWORK),
            lyricTintEnabled = if (version >= 9) flag(CapsuleSettings.LYRIC_TINT_ENABLED) else !legacyTintDisabled,
            lyricTintMode = if (legacyTintDisabled) LyricTintMode.OKLCH
                else LyricTintMode.fromStored(storedTintMode) ?: error("Invalid lyric tint mode"),
            showCover = if (version < 5) true else flag(CapsuleSettings.SHOW_COVER),
            showWaveform = if (version < 5) true else flag(CapsuleSettings.SHOW_WAVEFORM),
            lyricAlignment = if (version < 3) LyricAlignment.CENTER
            else LyricAlignment.valueOf(data.getString(CapsuleSettings.LYRIC_ALIGNMENT)),
            cameraFade = flag(CapsuleSettings.CAMERA_FADE),
            motion = LineMotion.fromStored(data.getString(CapsuleSettings.MOTION)) ?: error("Invalid line motion"),
            noLyricsMode = NoLyricsMode.valueOf(data.getString(CapsuleSettings.NO_LYRICS)),
            pauseBehavior = if (version >= 14) PauseBehavior.valueOf(data.getString(CapsuleSettings.PAUSE_BEHAVIOR))
            else lyrics.pauseBehavior,
            lyricSourceMode = data.optString(CapsuleSettings.LYRIC_SOURCE_MODE, "")
                .let { name -> LyricSourceMode.entries.firstOrNull { it.name == name } ?: lyrics.lyricSourceMode },
            interpolateWordLyrics = if (data.has(CapsuleSettings.INTERPOLATE_WORD_LYRICS)) data.optBoolean(CapsuleSettings.INTERPOLATE_WORD_LYRICS, lyrics.interpolateWordLyrics) else lyrics.interpolateWordLyrics,
            autoTranslateVi = if (data.has(CapsuleSettings.AUTO_TRANSLATE_VI)) data.optBoolean(CapsuleSettings.AUTO_TRANSLATE_VI, lyrics.autoTranslateVi) else lyrics.autoTranslateVi,
            translateTargetLang = data.optString(CapsuleSettings.TRANSLATE_TARGET_LANG, "")
                .let { name -> TranslateTargetLang.entries.firstOrNull { it.name == name } ?: lyrics.translateTargetLang },
            lockscreenBridge = if (data.has(CapsuleSettings.LOCKSCREEN_BRIDGE)) data.optBoolean(CapsuleSettings.LOCKSCREEN_BRIDGE, lyrics.lockscreenBridge) else lyrics.lockscreenBridge,
            aodUnlockWhitelist = if (data.has(CapsuleSettings.AOD_UNLOCK_WHITELIST)) data.optBoolean(CapsuleSettings.AOD_UNLOCK_WHITELIST, lyrics.aodUnlockWhitelist) else lyrics.aodUnlockWhitelist,
            autoClearCache = if (data.has(CapsuleSettings.AUTO_CLEAR_CACHE)) data.optBoolean(CapsuleSettings.AUTO_CLEAR_CACHE, lyrics.autoClearCache) else lyrics.autoClearCache,
            appWhitelistEnabled = if (data.has(CapsuleSettings.APP_WHITELIST_ENABLED)) data.optBoolean(CapsuleSettings.APP_WHITELIST_ENABLED, lyrics.appWhitelistEnabled) else lyrics.appWhitelistEnabled,
            appWhitelistPackages = if (data.has(CapsuleSettings.APP_WHITELIST_PACKAGES)) CapsuleSettings.decodePackageSet(data.optString(CapsuleSettings.APP_WHITELIST_PACKAGES, "")) else lyrics.appWhitelistPackages,
            tuning = tuning,
        )
        val helpHidden = if (version < 10) null else {
            root.getJSONObject("app_preferences").get(TRANSLATION_HELP_HIDDEN) as? Boolean
                ?: error("Invalid app preference")
        }
        return Snapshot(settings, helpHidden)
    }
}

internal fun SharedPreferences.Editor.writeLyricSettings(settings: CapsuleSettings) {
    putBoolean(CapsuleSettings.DOUBLE_LINE, settings.doubleLine)
    putString(CapsuleSettings.SECOND_ROW_CONTENT, settings.secondRowContent.name)
    putBoolean(CapsuleSettings.SWAP_TRANSLATION, settings.swapTranslation)
    putBoolean(CapsuleSettings.WORD_LYRICS, settings.wordLyrics)
    putBoolean(CapsuleSettings.CAMERA_WORD_AVOIDANCE, settings.cameraWordAvoidance)
    putBoolean(CapsuleSettings.INTRO_DOTS, settings.introDots)
    putString(CapsuleSettings.MISSING_TRANSLATION_MODE, settings.missingTranslationMode.name)
    putBoolean(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, settings.fallbackSingleLineExpand)
    remove(CapsuleSettings.LEGACY_TRANSLATION)
}

internal fun SharedPreferences.Editor.writeSettings(settings: CapsuleSettings) {
    putBoolean(CapsuleSettings.ENABLED, settings.enabled)
    writeLyricSettings(settings)
    putBoolean(CapsuleSettings.ARTWORK, settings.artwork)
    putBoolean(CapsuleSettings.LYRIC_TINT_ENABLED, settings.lyricTintEnabled)
    putString(CapsuleSettings.LYRIC_TINT_MODE, settings.lyricTintMode.name)
    putBoolean(CapsuleSettings.SHOW_COVER, settings.showCover)
    putBoolean(CapsuleSettings.SHOW_WAVEFORM, settings.showWaveform)
    remove("camera_shape") // Retired in version 4.
    remove("camera_fade_radius")
    remove("single_line_switch_mode")
    remove("double_line_switch_mode")
    remove("intro_dot_size")
    putString(CapsuleSettings.LYRIC_ALIGNMENT, settings.lyricAlignment.name)
    putBoolean(CapsuleSettings.CAMERA_FADE, settings.cameraFade)
    putString(CapsuleSettings.MOTION, settings.motion.name)
    putString(CapsuleSettings.NO_LYRICS, settings.noLyricsMode.name)
    putString(CapsuleSettings.PAUSE_BEHAVIOR, settings.pauseBehavior.name)
    putString(CapsuleSettings.LYRIC_SOURCE_MODE, settings.lyricSourceMode.name)
    putBoolean(CapsuleSettings.INTERPOLATE_WORD_LYRICS, settings.interpolateWordLyrics)
    putBoolean(CapsuleSettings.AUTO_TRANSLATE_VI, settings.autoTranslateVi)
    putString(CapsuleSettings.TRANSLATE_TARGET_LANG, settings.translateTargetLang.name)
    putBoolean(CapsuleSettings.LOCKSCREEN_BRIDGE, settings.lockscreenBridge)
    putBoolean(CapsuleSettings.AOD_UNLOCK_WHITELIST, settings.aodUnlockWhitelist)
    remove("prevent_capsule_collapse")
    putBoolean(CapsuleSettings.AUTO_CLEAR_CACHE, settings.autoClearCache)
    putBoolean(CapsuleSettings.APP_WHITELIST_ENABLED, settings.appWhitelistEnabled)
    putString(CapsuleSettings.APP_WHITELIST_PACKAGES, CapsuleSettings.encodePackageSet(settings.appWhitelistPackages))
    CapsuleSettings.retiredTintKeys.forEach { remove(it) }
    CapsuleTuning.entries.forEach { putInt(it.key, settings[it]) }
}
