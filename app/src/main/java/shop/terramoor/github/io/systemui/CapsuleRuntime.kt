package shop.terramoor.github.io.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface.HookHandle
import shop.terramoor.github.io.diagnostics.DiagnosticLog
import shop.terramoor.github.io.lyrics.LyricBridgeProvider
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleSettingsUpdate
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.NoLyricsMode
import shop.terramoor.github.io.settings.PauseBehavior
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns a single capsule and session, with all UI and callback state confined to the main thread. */
internal class CapsuleRuntime(
    private val host: NativeContract.Host,
    private val diagnostics: DiagnosticLog,
    private val modulePackage: String,
    private val remotePreferences: () -> SharedPreferences,
    private val installPlugin: (NativeContract.Plugin, CapsuleRuntime) -> List<HookHandle>,
) {
    private val main = Handler(Looper.getMainLooper())
    private val onlineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val statusExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val bridgedPayloadsByTitle = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var fluidCloudHooked: Boolean = false
    @Volatile var lockscreenHooked: Boolean = false
    @Volatile var aodHooked: Boolean = false
    @Volatile private var hookStatusReported: Boolean = false
    private val readingNativeMetadata = ThreadLocal<Boolean>()
    private var lastReportedNowPlayingKey: String? = null
    private var lastReportedCoverTrack: String? = null
    private var lastReportedLyricsHash: Int = 0
    private var aodTitleView = WeakReference<android.widget.TextView>(null)
    private var aodArtistView = WeakReference<android.widget.TextView>(null)
    private val aodOverlay = AodLyricOverlay { onMain("AOD attached") { renderAodOnly() } }
    private val vocalTracker = VocalEnergyTracker()
    private var preferences: SharedPreferences? = null
    private var preferenceAttempts = 0
    private var settingsUpdateRegistered = false
    private var receivedCommittedSettings = false
    private var settings = CapsuleSettings(enabled = false)
    private var tintDrawFrame: String? = null
    private var hostView: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var root: ViewGroup? = null
    private var plugin: NativeContract.Plugin? = null
    private var pluginHooks = emptyList<HookHandle>()
    private var viewportHookInstalled = false
    private var rejectedLoader = WeakReference<ClassLoader>(null)
    private var mediaController = WeakReference<Any>(null)
    private var dispatched: StatusBarNotification? = null
    private var media: NativeContract.Media? = null
    private var mediaMissingSince: Long? = null
    private var destroyedToken: MediaSession.Token? = null
    private var session: Session? = null
    private val pauseTimeout = CapsulePauseTimeout<NativeContract.PauseOwner>()
    private val tintArtworkSelection = TintArtworkSelection<Pair<MediaSession.Token, Track>, Bitmap>()
    private var labels: CapsuleLabels? = null
    private var mediaCardScene: MediaCardScene? = null
    private val interludeStatusBarSpace = InterludeStatusBarSpace()
    private val centeredMedia = WeakHashMap<ViewGroup, String>()
    private val originalCapsuleMetrics = WeakHashMap<ViewGroup, Pair<Int, Int>>()
    private val centerLocation = IntArray(2)
    private var renderedShown: Boolean? = null
    private var layoutChanged = false
    private var scheduledTick: LyricBoundaryTick? = null
    private var status: String? = null
    private val stageMessages = mutableMapOf<String, String>()

    private val refresh = Runnable { protect("Capsule update") { refreshTarget(forceRender = true) } }
    private val tick = Runnable {
        scheduledTick = null
        protect("Lyric rendering", ::render)
    }
    private val connect = Runnable { protect("Preferences reconnect", ::loadPreferences) }
    private val retryMedia = Runnable { protect("Media handoff") { readMedia(); postRefresh() } }
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { if (settings.enabled) layoutChanged = true }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val refreshPending = main.hasCallbacks(refresh)
        if (refreshPending || layoutChanged) {
            if (refreshPending) main.removeCallbacks(refresh)
            protect("Capsule update") { refreshTarget(forceRender = refreshPending) }
        }
        val binding = labels
        binding?.enforceNativeVisibility()
        if (binding != null && !binding.nativeWhilePaused && !binding.hasInterludeViewport &&
            !binding.isCollapsingForPause && !binding.isBouncingForTrack) {
            binding.maintainNativeSizing()
        }
        if (binding != null && binding.isCollapsingForPause) {
            protect("Capsule pause collapse") { binding.preparePauseCollapse(SystemClock.elapsedRealtime()) }
        } else if (binding != null && binding.isBouncingForTrack) {
            protect("Capsule track transition") { binding.prepareTrackTransition(SystemClock.elapsedRealtime()) }
        }
        if (binding != null && !binding.isCollapsingForPause && (renderedShown != binding.target.capsule.isShown ||
                !binding.nativeWhilePaused && (settings.artwork || settings.lyricTintEnabled) &&
                session?.artworkChanged(binding.target.artwork) == true)) {
            protect("Capsule visibility or artwork", ::render)
        }
        val viewportBinding = labels?.takeIf { it.hasInterludeViewport && !it.isCollapsingForPause && !it.isBouncingForTrack }
        if (viewportBinding != null) {
            protect("Capsule viewport") { prepareViewport(viewportBinding) }
        } else if (labels?.isCollapsingForPause != true && labels?.isBouncingForTrack != true) {
            updateInterludeStatusBarSpace(compact = false)
        }
        true
    }
    private val applyPreferences = Runnable {
        if (!receivedCommittedSettings) protect("Preferences", ::readPreferences)
    }
    private val onPreferences = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        trace("preferences", "CHANGE_RECEIVED")
        main.removeCallbacks(applyPreferences)
        main.post(applyPreferences)
    }

    private fun readPreferences() = applySettings(
        preferences?.let(CapsuleSettings::read) ?: CapsuleSettings(enabled = false),
    )

    private fun applySettings(next: CapsuleSettings) {
        val previous = settings
        settings = next
        diagnostics.note("Settings read, changed=${settings != previous}, $settings")
        trace("tint/settings", "module=${settings.enabled}, enabled=${settings.lyricTintEnabled}, " +
            "algorithm=${settings.lyricTintMode}, strength=${settings[CapsuleTuning.TINT_STRENGTH]}, " +
            "background=${settings.artwork}, firstOpacity=${settings[CapsuleTuning.FIRST_ROW_OPACITY]}, " +
            "secondOpacity=${settings[CapsuleTuning.SECOND_ROW_OPACITY]}")
        if (settings == previous) return
        if (settings.introDots != previous.introDots) session?.lastLoggedLine = null
        if (!settings.enabled) {
            vocalTracker.setActive(false)
            aodOverlay.restoreNative()
            stopObservation()
            setStatus("Lyrics disabled")
            return
        }
        if (!settings.interpolateWordLyrics) {
            vocalTracker.setActive(false)
        }
        if (!previous.enabled) {
            startObservation()
            readMedia()
        }
        val sourceChanged = settings.lyricSourceMode != previous.lyricSourceMode ||
            settings.interpolateWordLyrics != previous.interpolateWordLyrics
        val translationChanged = settings.translateTargetLang != previous.translateTargetLang ||
            settings.autoTranslateVi != previous.autoTranslateVi
        if (sourceChanged || translationChanged) {
            session?.reloadOnlineLyricsAndTranslation(sourceChanged, translationChanged)
        }
        postRefresh()
        hostView?.invalidate()
    }

    fun isLockscreenBridgeEnabled(): Boolean = settings.enabled && settings.lockscreenBridge

    fun isAodWhitelistUnlocked(): Boolean = settings.enabled && settings.aodUnlockWhitelist

    fun shouldKeepAodDozeActive(): Boolean = settings.enabled && aodOverlay.isActivelyPlaying

    fun bindAodMediaLayout(layout: ViewGroup, nativeRoot: View?) = onMain("AOD layout bind") {
        loadPreferences()
        aodOverlay.bind(layout, nativeRoot)
        ensureSessionForAodOrLockscreen()
        renderAodOnly()
    }

    fun onAodMediaLayoutVisible(layout: ViewGroup, nativeRoot: View?) = onMain("AOD layout visible") {
        loadPreferences()
        aodOverlay.bind(layout, nativeRoot)
        ensureSessionForAodOrLockscreen()
        renderAodOnly()
    }

    fun onAodRootVisibilityChanged(visible: Boolean) = onMain("AOD visibility") {
        if (visible) {
            loadPreferences()
            ensureSessionForAodOrLockscreen()
            renderAodOnly()
        } else {
            aodOverlay.onAodHidden()
        }
    }

    fun bindAodMediaViews(
        player: ViewGroup?,
        playerContainer: View?,
        albumView: android.widget.ImageView?,
        title: android.widget.TextView?,
        artist: android.widget.TextView?,
    ) = onMain("AOD media bind") {
        loadPreferences()
        if (player != null) {
            aodOverlay.bind(player, playerContainer, albumView)
        }
        if (title != null) aodTitleView = WeakReference(title)
        if (artist != null) aodArtistView = WeakReference(artist)
        ensureSessionForAodOrLockscreen()
        renderAodOnly()
    }

    private fun updateAodOverlay(
        watch: Session?,
        timeline: Timeline?,
        index: Int,
        mainText: String?,
        translationText: String?,
        countdown: shop.terramoor.github.io.lyrics.LyricCountdown? = null,
        positionMs: Long = 0L,
        nextLineDelayMs: Long? = null,
    ) {
        val playing = watch != null && !watch.isPaused
        val enabled = isLockscreenBridgeEnabled() && watch != null &&
            !(watch.isPaused && settings.pauseBehavior == PauseBehavior.RESTORE_NATIVE)
        val nextLine = if (countdown == null && timeline != null && index >= 0) {
            ((index + 1) until timeline.lines.size)
                .asSequence()
                .mapNotNull { timeline.lines[it].text.trim().takeIf(String::isNotEmpty) }
                .firstOrNull()
        } else null
        aodOverlay.update(
            enabled = enabled,
            playing = playing,
            songTitle = watch?.aodSongTitle(),
            songArtist = watch?.aodSongArtist(),
            artwork = watch?.aodArtwork(),
            main = mainText,
            translation = translationText,
            next = nextLine,
            countdown = countdown,
            positionMs = positionMs,
            nextLineDelayMs = nextLineDelayMs,
        )
    }

    private fun updateAodLyricViews(frame: LyricFrame?) {
        if (!isLockscreenBridgeEnabled()) return
        val titleTv = aodTitleView.get()?.takeIf { it.isAttachedToWindow } ?: return
        val artistTv = aodArtistView.get()?.takeIf { it.isAttachedToWindow }
        val first = frame?.rows?.first?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val second = frame.rows.second?.trim()?.takeIf { it.isNotEmpty() }
        if (titleTv.text?.toString() != first) {
            titleTv.text = first
        }
        if (artistTv != null && second != null && artistTv.text?.toString() != second) {
            artistTv.text = second
        }
    }

    private fun resolveAppContext(): android.content.Context? =
        hostView?.context?.applicationContext ?: runCatching {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull()

    fun reportHookStatusNow() {
        val ctx = resolveAppContext() ?: return
        val uri = android.net.Uri.parse("content://$modulePackage${LyricBridgeProvider.AUTHORITY_SUFFIX}")
        val args = android.os.Bundle().apply {
            putBoolean(LyricBridgeProvider.KEY_FLUID_CLOUD, fluidCloudHooked)
            putBoolean(LyricBridgeProvider.KEY_LOCKSCREEN, lockscreenHooked)
            putBoolean(LyricBridgeProvider.KEY_AOD, aodHooked)
        }
        hookStatusReported = true
        statusExecutor.execute {
            runCatching {
                ctx.contentResolver.call(uri, LyricBridgeProvider.METHOD_REPORT_HOOK_STATUS, null, args)
            }
        }
    }

    private fun serializeTimelineForUi(timeline: Timeline): String {
        val arr = org.json.JSONArray()
        for (line in timeline.lines) {
            if (line.isInstrumentalGap) continue
            val text = line.text.trim()
            if (text.isEmpty()) continue
            val obj = org.json.JSONObject()
                .put("t", line.startMs)
                .put("l", text)
            val tr = line.translation?.trim()?.takeIf { it.isNotEmpty() }
            if (tr != null) obj.put("tr", tr)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun reportNowPlayingNow(
        title: String,
        artist: String,
        source: String,
        wordTimed: Boolean,
        artwork: Bitmap? = null,
        timeline: Timeline? = null,
    ) {
        val ctx = resolveAppContext() ?: return
        val trackKey = "$title|$artist"
        val needCover = artwork != null && !artwork.isRecycled && trackKey != lastReportedCoverTrack
        val lyricsHash = if (timeline != null && source.isNotBlank()) System.identityHashCode(timeline) else 0
        val needLyrics = lyricsHash != 0 && lyricsHash != lastReportedLyricsHash
        val sig = "$trackKey|$source|$wordTimed"
        if (sig == lastReportedNowPlayingKey && !needCover && !needLyrics) return
        lastReportedNowPlayingKey = sig
        if (needCover) lastReportedCoverTrack = trackKey
        if (needLyrics) lastReportedLyricsHash = lyricsHash
        if (title.isBlank() || source.isBlank()) {
            if (title.isBlank()) lastReportedCoverTrack = null
            lastReportedLyricsHash = 0
        }
        val uri = android.net.Uri.parse("content://$modulePackage${LyricBridgeProvider.AUTHORITY_SUFFIX}")
        val artworkCopy = if (needCover && artwork != null) {
            runCatching {
                val longest = maxOf(artwork.width, artwork.height).coerceAtLeast(1)
                if (longest <= 128) artwork.copy(Bitmap.Config.ARGB_8888, false)
                else Bitmap.createScaledBitmap(
                    artwork,
                    (artwork.width * 128f / longest).roundToInt().coerceAtLeast(1),
                    (artwork.height * 128f / longest).roundToInt().coerceAtLeast(1),
                    true,
                )
            }.getOrNull()
        } else null
        val lyricsJson = when {
            needLyrics && timeline != null -> serializeTimelineForUi(timeline)
            source.isBlank() || title.isBlank() -> ""
            else -> null
        }
        val watchRef = session
        statusExecutor.execute {
            runCatching {
                val coverBytes = artworkCopy?.let { bmp ->
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
                val args = android.os.Bundle().apply {
                    putString(LyricBridgeProvider.EXTRA_TITLE, title)
                    putString(LyricBridgeProvider.EXTRA_ARTIST, artist)
                    putString(LyricBridgeProvider.EXTRA_SOURCE, source)
                    putBoolean(LyricBridgeProvider.EXTRA_WORD_TIMED, wordTimed)
                    if (coverBytes != null) putByteArray(LyricBridgeProvider.EXTRA_COVER_BYTES, coverBytes)
                    if (lyricsJson != null) putString(LyricBridgeProvider.EXTRA_LYRICS_LINES_JSON, lyricsJson)
                }
                val reply = ctx.contentResolver.call(uri, LyricBridgeProvider.METHOD_REPORT_NOW_PLAYING, null, args)
                val savedOffset = reply?.getLong(LyricBridgeProvider.EXTRA_OFFSET_MS, 0L) ?: 0L
                if (watchRef != null) {
                    main.post {
                        if (session === watchRef) watchRef.initTrackOffsetIfNeeded(title, artist, savedOffset)
                    }
                }
            }
        }
    }

    fun loadLockscreenLyricSync(
        loader: ClassLoader,
        metadata: MediaMetadata?,
        song: String?,
        artist: String?,
    ): Any? {
        if (!isLockscreenBridgeEnabled()) return null
        val title = song?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: return null
        val resolvedArtist = artist?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val cached = bridgedPayloadsByTitle[title.trim().lowercase(java.util.Locale.ROOT)]
        val payloadJson = cached ?: run {
            val ctx = resolveAppContext() ?: return null
            val sourceMode = settings.lyricSourceMode
            if (sourceMode == shop.terramoor.github.io.settings.LyricSourceMode.EXTERNAL_ONLY) return null
            val uri = android.net.Uri.parse("content://$modulePackage${LyricBridgeProvider.AUTHORITY_SUFFIX}")
            val args = android.os.Bundle().apply {
                putString(LyricBridgeProvider.EXTRA_TITLE, title)
                putString(LyricBridgeProvider.EXTRA_ARTIST, resolvedArtist)
                putLong(LyricBridgeProvider.EXTRA_DURATION_MS, durationMs)
                putString(LyricBridgeProvider.EXTRA_SOURCE_MODE, sourceMode.name)
            }
            val reply = runCatching {
                ctx.contentResolver.call(uri, LyricBridgeProvider.METHOD_FETCH_LYRICS, null, args)
            }.getOrNull()
            reply?.getString(LyricBridgeProvider.EXTRA_PAYLOAD)?.takeIf { it.isNotBlank() }?.also {
                storeBridgedPayload(title, it)
            }
        } ?: return null

        val rawLrc = runCatching { org.json.JSONObject(payloadJson).optString("lyric") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val lrc = stripInlineWordTimestamps(rawLrc)

        return runCatching {
            val lyricDataCls = loader.loadClass("com.android.systemui.media.controls.models.player.OplusMediaLyricData")
            val parserCls = loader.loadClass("com.android.systemui.media.controls.models.player.LyricParser")
            val parserInstance = parserCls.getField("INSTANCE").get(null)
            val parsedLines = parserCls.getMethod("parse", String::class.java).invoke(parserInstance, lrc) as? List<*>
            if (parsedLines.isNullOrEmpty()) return null
            val ctor = lyricDataCls.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java, String::class.java)
            val data = ctor.newInstance(3, title, resolvedArtist, lrc)
            lyricDataCls.getMethod("setLines", List::class.java).invoke(data, parsedLines)
            Log.i(TAG, "[lockscreen/aod] Injected ${parsedLines.size} lyric lines for '$title'")
            data
        }.onFailure {
            Log.w(TAG, "Lockscreen/AOD OplusMediaLyricData build failed: ${it.message}")
        }.getOrNull()
    }

    fun injectSeedlingLyrics(title: String?, artist: String?, lrc: String) {
        if (!isLockscreenBridgeEnabled() || !host.isSeedlingMediaMode) return
        val controller = mediaController.get() ?: return
        val cleanLrc = stripInlineWordTimestamps(lrc)
        runCatching {
            val loader = controller.javaClass.classLoader ?: return
            val parserCls = loader.loadClass("com.android.systemui.media.controls.models.player.LyricParser")
            val parserInstance = parserCls.getField("INSTANCE").get(null)
            val parsedLines = parserCls.getMethod("parse", String::class.java).invoke(parserInstance, cleanLrc) as? List<*>
            if (parsedLines.isNullOrEmpty()) return
            val seedlingLyricCls = loader.loadClass("com.oplus.systemui.seedlingservice.mediaControl.LyricData")
            val ctor = seedlingLyricCls.getConstructor(
                String::class.java, String::class.java, List::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            val lyricData = ctor.newInstance(title.orEmpty(), artist.orEmpty(), parsedLines, true, true)
            host.injectSeedlingLyricData(controller, lyricData)
            Log.i(TAG, "[lockscreen/aod] Updated SeedlingMediaData with ${parsedLines.size} lines")
        }.onFailure {
            Log.w(TAG, "injectSeedlingLyrics failed: ${it.message}")
        }
    }

    private fun stripInlineWordTimestamps(lrc: String): String {
        val noAngleTags = INLINE_WORD_TIMESTAMP_REGEX.replace(lrc, "")
        // Normalize 3-digit millisecond line timestamps [mm:ss.xxx] to standard 2-digit [mm:ss.xx] for ColorOS LyricParser
        return THREE_DIGIT_LINE_TS_REGEX.replace(noAngleTags) { m ->
            "[${m.groupValues[1]}:${m.groupValues[2]}.${m.groupValues[3].take(2)}]"
        }
    }

    fun bridgeLyricInfo(metadata: MediaMetadata): String? {
        if (readingNativeMetadata.get() == true) return null
        if (!settings.enabled || !settings.lockscreenBridge) return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.lowercase(java.util.Locale.ROOT)
            ?.takeIf { it.isNotEmpty() } ?: return null
        return bridgedPayloadsByTitle[title]
    }

    private fun readNativeLyricInfo(data: MediaMetadata?): String? {
        if (data == null) return null
        readingNativeMetadata.set(true)
        return try {
            data.getString(LyricPayload.KEY)?.takeIf { it.isNotBlank() }
        } finally {
            readingNativeMetadata.set(false)
        }
    }

    private fun storeBridgedPayload(title: String?, payload: String) {
        val key = title?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return
        if (bridgedPayloadsByTitle.size > 32) bridgedPayloadsByTitle.clear()
        val sanitized = runCatching {
            val obj = org.json.JSONObject(payload)
            val lrc = obj.optString("lyric")
            if (lrc.isNotEmpty()) {
                obj.put("lyric", stripInlineWordTimestamps(lrc))
            }
            val rawLrc = obj.optString("rawLyric")
            if (rawLrc.isNotEmpty()) {
                obj.put("rawLyric", stripInlineWordTimestamps(rawLrc))
            }
            obj.toString()
        }.getOrDefault(payload)
        bridgedPayloadsByTitle[key] = sanitized
    }

    private fun buildTranslationLrc(timeline: Timeline): String {
        val sb = StringBuilder()
        for (line in timeline.lines) {
            val tr = line.translation?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val ms = line.startMs.coerceAtLeast(0L)
            val min = ms / 60_000L
            val sec = (ms % 60_000L) / 1000L
            val cs = (ms % 1000L) / 10L
            sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", min, sec, cs, tr))
        }
        return sb.toString()
    }

    fun hostChanged(instance: Any) = onMain("Capsule host") {
        val view = host.view(instance)
        val displayId = view.display?.displayId
        if (displayId != Display.DEFAULT_DISPLAY) {
            trace("host", "NOT_ON_PRIMARY_DISPLAY, id=$displayId, attached=${view.isAttachedToWindow}")
            return@onMain
        }
        if (hostView !== view) {
            stopObservation(releaseMedia = false)
            hostView = view
            trace("host", "ATTACHED, children=${view.childCount}")
        }
        loadPreferences()
        if (!hookStatusReported) reportHookStatusNow()
        startObservation()
        postRefresh()
    }

    fun hostDetached(instance: Any) = onMain("Capsule detach") {
        if (hostView === host.view(instance)) {
            stopObservation(releaseMedia = false)
            hostView = null
            trace("host", "DETACHED")
            main.removeCallbacks(connect)
        }
    }

    fun pluginRemoved(instance: Any) = onMain("Capsule plugin detach") {
        if (hostView === host.view(instance)) {
            detachPlugin()
            diagnostics.note("Native capsule plugin detached")
            postRefresh()
        }
    }

    fun mediaChanged(instance: Any, sent: Boolean, notification: StatusBarNotification?) = onMain("Media notification") {
        trace("media callback", "sent=$sent, route=${if (notification == null) "controller" else "dispatch"}")
        if (sent || mediaController.get() === instance || mediaController.get() == null) {
            mediaController = WeakReference(instance)
            dispatched = notification
            loadPreferences()
            if (settings.enabled) {
                readMedia()
                ensureSessionForAodOrLockscreen()
                postRefresh()
            }
        }
    }

    fun mediaReleased(instance: Any) = onMain("Media release") {
        if (mediaController.get() === instance) {
            mediaController.clear()
            dispatched = null
            trace("media callback", "CONTROLLER_RELEASED")
            clearMediaRetry()
            media = null
            destroyedToken = null
            releaseSession()
        }
    }

    fun containerLaidOut(container: Any) {
        val currentRoot = root ?: return
        if (settings.enabled && plugin?.belongs(container, currentRoot) == true) layoutChanged = true
    }

    private fun restoreCapsuleMetrics(capsule: ViewGroup) {
        val orig = originalCapsuleMetrics[capsule] ?: return
        val (origWidth, origMinWidth) = orig
        if (capsule.minimumWidth != origMinWidth) capsule.minimumWidth = origMinWidth
        val params = capsule.layoutParams
        if (params != null && params.width != origWidth) params.width = origWidth
    }

    fun prepareCapsuleMeasure(instance: Any) {
        val capsule = instance as? ViewGroup ?: return
        if (!settings.enabled) {
            restoreCapsuleMetrics(capsule)
            return
        }
        val active = media?.takeUnless { it.token == destroyedToken } ?: run {
            restoreCapsuleMetrics(capsule)
            return
        }
        if (!settings.allowsApp(active.owner)) {
            restoreCapsuleMetrics(capsule)
            return
        }
        val watch = session ?: return
        if (watch.token != active.token) return
        if (watch.isPaused && settings.pauseBehavior == PauseBehavior.RESTORE_NATIVE && labels?.isCollapsingForPause != true) {
            restoreCapsuleMetrics(capsule)
            return
        }
        val contract = plugin ?: return
        if (!contract.hasOwner(capsule, active.key)) return
        val normalWidth = capsuleWidthPixels(settings[CapsuleTuning.ISLAND_WIDTH], capsule.resources.displayMetrics.density)
        if (capsule !in originalCapsuleMetrics) {
            val rawWidth = capsule.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
            val origWidth = if (rawWidth >= normalWidth) ViewGroup.LayoutParams.WRAP_CONTENT else rawWidth
            val origMinWidth = if (capsule.minimumWidth >= normalWidth) 0 else capsule.minimumWidth
            originalCapsuleMetrics[capsule] = origWidth to origMinWidth
        }
        if (capsule.minimumWidth != normalWidth) capsule.minimumWidth = normalWidth
        val params = capsule.layoutParams
        if (params != null && params.width != normalWidth) params.width = normalWidth
    }

    fun viewportHookInstalled() {
        viewportHookInstalled = true
    }

    fun nativeWidthAnimationStarted(instance: Any) {
        val binding = labels?.takeIf { it.target.capsule === instance } ?: return
        updateInterludeStatusBarSpace(compact = false)
        if (currentMediaCardScene(binding) == null) binding.suspendViewport()
    }

    fun beginMediaCardScene(container: ViewGroup, name: String) {
        if (Looper.myLooper() != main.looper || !settings.enabled || !viewportHookInstalled) return
        val currentRoot = root ?: return
        val contract = plugin ?: return
        if (!contract.belongs(container, currentRoot)) return
        if (labels?.target?.capsule?.parent === container) updateInterludeStatusBarSpace(compact = false)
        mediaCardScene = null
        if (name !in INTERLUDE_HANDOFF_SCENES) return
        val active = media ?: return
        if (session?.token != active.token || !contract.isSingleLane(container)) return
        // During card dismissal the OEM may lay out a replacement capsule after this callback.
        // Scope the handoff to the container and media owner, not to the old instance of CapsuleView.
        val scene = MediaCardScene(container, active.token, active.key, name, SystemClock.elapsedRealtime() + CARD_SCENE_MS)
        mediaCardScene = scene
        if (labels?.hasInterludeViewport == true) trace("card/viewport", "$name BEGIN")
    }

    fun endMediaCardScene(container: ViewGroup) {
        if (Looper.myLooper() != main.looper) return
        val scene = mediaCardScene?.takeIf { it.container === container } ?: return
        scene.ended = true
        if (labels?.hasInterludeViewport == true) trace("card/viewport", "${scene.name} END")
    }

    private fun currentMediaCardScene(binding: CapsuleLabels): MediaCardScene? {
        val scene = mediaCardScene ?: return null
        val active = media
        if (active?.token != scene.token || session?.token != scene.token || active.key != scene.key ||
            SystemClock.elapsedRealtime() >= scene.expiresAt) {
            mediaCardScene = null
            return null
        }
        return scene.takeIf { binding.target.capsule.parent === it.container &&
            binding.hasInterludeViewport && !binding.nativeWhilePaused }
    }

    private fun prepareViewport(binding: CapsuleLabels) {
        val contract = plugin
        val active = media
        val capsule = binding.target.capsule
        val scene = currentMediaCardScene(binding)
        val ownsViewport = viewportHookInstalled && contract != null && active != null &&
            session?.token == active.token && contract.hasOwner(capsule, active.key) &&
            contract.supportsInterludeViewport(capsule)
        val hasDrawingBounds = capsule.isShown && capsule.isHardwareAccelerated && capsule.isLaidOut &&
            capsule.width > 0 && capsule.height > 0 && isAlignedWithCutout(capsule)
        val settled = hasDrawingBounds && !capsule.isLayoutRequested &&
            capsule.width == capsule.measuredWidth && capsule.height == capsule.measuredHeight &&
            capsule.scaleX == 1f && capsule.scaleY == 1f && capsule.translationX == 0f &&
            contract?.isAnimatingWidth(capsule) == false
        // Hidden or zero-sized frames are part of the native animation. Keep the current
        // outline until the next drawable frame rather than restarting from full width.
        if (scene != null && ownsViewport && !hasDrawingBounds) {
            updateInterludeStatusBarSpace(compact = false)
            return
        }
        val canCompact = ownsViewport && (settled || scene != null && hasDrawingBounds)
        val applied = binding.prepareViewport(SystemClock.elapsedRealtime(), canCompact, resumeInterlude = scene != null,
            animatedWidth = if (scene != null) contract?.animatedDrawingWidth(capsule) else null)
        updateInterludeStatusBarSpace(compact = scene == null && settled && applied &&
            capsule.windowVisibility == View.VISIBLE && binding.shouldReserveCompactStatusBarSpace)
        if (scene != null && applied) sampleCoverAnchor(scene, binding)
        if (scene?.ended == true && applied && settled) {
            mediaCardScene = null
            trace("card/viewport", "${scene.name} RESUMED ${binding.viewportWidth}px/${capsule.width}px, " +
                "peak=${scene.peakDrawnWidth}px, widestUnclipped=${scene.widestUnclipped}px, " +
                "coverSamples=${scene.coverSamples}, coverError=${scene.maxCoverError.roundToInt()}px")
        }
    }

    private fun updateInterludeStatusBarSpace(compact: Boolean) {
        if (Looper.myLooper() != main.looper) return
        val space = plugin?.statusBarSpace ?: return
        try {
            val width = interludeStatusBarSpace.update(compact, space::width, space::publish) ?: return
            trace("interlude/statusbar", "reserved=${space.width()}px, requested=${width}px")
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            report("Interlude status bar placeholder", failure)
        }
    }

    private fun sampleCoverAnchor(scene: MediaCardScene, binding: CapsuleLabels) {
        val anchor = binding.coverAnchorX ?: return
        val cover = binding.target.artwork
        if (!cover.isShown) return
        val capsule = binding.target.capsule
        val coverScale = cover.horizontalScreenScale() ?: return
        val capsuleScale = capsule.horizontalScreenScale() ?: return
        capsule.getLocationOnScreen(centerLocation)
        val left = centerLocation[0]
        val expected = left + anchor * capsuleScale
        cover.getLocationOnScreen(centerLocation)
        val actual = centerLocation[0] + cover.width / 2f * coverScale
        if (scene.coverSamples == 0) {
            val center = left + capsule.width / 2f * capsuleScale
            trace("card/viewport", "${scene.name} COVER_FIRST ${binding.viewportWidth}px, " +
                "coverFromCenter=${(actual - center).roundToInt()}px")
        }
        scene.coverSamples++
        scene.maxCoverError = maxOf(scene.maxCoverError, abs(actual - expected))
    }

    private fun isAlignedWithCutout(capsule: View): Boolean {
        val scale = capsule.horizontalScreenScale() ?: return false
        val center = capsule.topDisplayCutout()?.exactCenterX() ?: return true
        return abs(center - visibleCenter(capsule, scale, null)) <= 2f
    }

    fun draw(instance: Any, canvas: Canvas) {
        val contract = plugin ?: return
        val candidate = media ?: return
        if (labels == null && settings.enabled && instance is ViewGroup &&
            settings.allowsApp(candidate.owner) && contract.hasOwner(instance, candidate.key)) {
            // Re-bind synchronously on the very first draw after shade/card dismissal so no black frame flashes.
            protect("Capsule immediate draw bind") { refreshTarget(forceRender = true) }
        }
        val binding = labels ?: return
        if (binding.nativeWhilePaused) return
        val capsule = binding.target.capsule
        if (capsule !== instance || session?.token != candidate.token || !contract.hasOwner(capsule, candidate.key)) return
        val animatedWidth = contract.animatedDrawingWidth(capsule)
        if (animatedWidth == null && centeredMedia[capsule] != candidate.key && capsule.width > 0) {
            val center = capsule.topDisplayCutout()?.exactCenterX()
            val scale = capsule.horizontalScreenScale()
            if (center != null && scale != null && abs(center - visibleCenter(capsule, scale, null)) <= 2f) {
                centeredMedia[capsule] = candidate.key
            }
        }
        val result = binding.draw(canvas, animatedWidth)
        currentMediaCardScene(binding)?.takeIf { result == "LYRICS_DRAWN" }?.let {
            val visibleWidth = minOf(binding.viewportWidth, animatedWidth ?: capsule.width)
            if (it.peakDrawnWidth == 0) {
                trace("card/viewport", "${it.name} FIRST_DRAW ${visibleWidth}px/${capsule.width}px, native=$animatedWidth")
            }
            it.peakDrawnWidth = maxOf(it.peakDrawnWidth, visibleWidth)
            if (visibleWidth == capsule.width) it.widestUnclipped = maxOf(it.widestUnclipped, visibleWidth)
        }
        trace("draw", result)
        val frame = tintDrawFrame
        if (lastTintDrawFrame != frame || lastTintDrawResult != result) {
            lastTintDrawFrame = frame
            lastTintDrawResult = result
            if (frame != null) trace("tint/draw", "result=$result, $frame, ${binding.paintDiagnostics()}")
        }
    }

    fun anchorMediaClose(shape: NativeContract.Plugin.CloseAnimation.Shape, animator: ValueAnimator) {
        if (Looper.myLooper() != main.looper || !settings.enabled) return
        val contract = plugin ?: return
        val currentRoot = root ?: return
        val capsule = shape.capsule
        val container = shape.container
        val key = centeredMedia[capsule] ?: return
        if (capsule.parent !== container || !contract.belongs(container, currentRoot) ||
            !contract.hasOwner(capsule, key) || !contract.isSingleLane(container)) return

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                nativeWidthAnimationStarted(capsule)
            }
        })
        var reported = false
        var valid = true
        // The OEM listener writes the animated rectangle first. Keep its size and move both edges
        // by the same amount using the current on-screen position, even if the parent remeasures.
        animator.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
            override fun onAnimationUpdate(animation: ValueAnimator) {
                if (!valid) return
                try {
                    if (!settings.enabled || plugin !== contract || root !== currentRoot ||
                        centeredMedia[capsule] != key ||
                        capsule.parent !== container || !capsule.isAttachedToWindow ||
                        !contract.hasOwner(capsule, key) || !contract.isSingleLane(container)) {
                        valid = false
                        return
                    }
                    val center = capsule.topDisplayCutout()?.exactCenterX() ?: return
                    val parentScale = container.horizontalScreenScale() ?: return
                    val capsuleScale = capsule.scaleX * parentScale
                    if (capsuleScale <= 0f || capsule.width <= 0) return
                    val nativeCenter = visibleCenter(capsule, capsuleScale, contract.animatedDrawingWidth(capsule))
                    val offset = capsuleCloseOffset(center, nativeCenter, parentScale)
                    if (offset == 0) return
                    capsule.offsetLeftAndRight(offset)
                    if (!reported) {
                        reported = true
                        trace("close geometry", "offsetPx=$offset, nativeCenter=$nativeCenter, cutoutCenter=$center")
                    }
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    valid = false
                    report("Media close frame", failure)
                }
            }
        })
    }

    private fun visibleCenter(capsule: View, screenScale: Float, animatedWidth: Int?): Float {
        capsule.getLocationOnScreen(centerLocation)
        val center = if (animatedWidth == null) capsule.width / 2f
        else nativeCapsuleClipLeft(capsule.measuredWidth, animatedWidth) + animatedWidth / 2f
        return centerLocation[0] + center * screenScale
    }

    private fun View.horizontalScreenScale(): Float? {
        var view: View? = this
        var scale = 1f
        while (view != null) {
            if (view.rotation != 0f || view.rotationX != 0f || view.rotationY != 0f) return null
            scale *= view.scaleX
            view = view.parent as? View
        }
        return scale.takeIf { it.isFinite() && it > 0f }
    }

    fun stabilizeMediaContentFrame(callback: Any): (() -> Unit)? {
        val contract = plugin ?: return null
        val binding = labels ?: return null
        if (binding.nativeWhilePaused) return null
        val target = binding.target
        val active = target.capsule
        val key = media?.takeUnless { it.token == destroyedToken }?.key ?: return null
        if (!active.isShown || active.width <= 0 || !contract.hasOwner(active, key)) return null
        if (contract.isAnimatingWidth(active)) return null
        val animation = contract.animation ?: return null
        val view = animation.view(callback)
        // A frame callback may run between the OEM's measurements. Use only the existing binding.
        if (!target.isContentPage(view)) return null
        return animation.stabilize(callback, view)
    }

    fun retainPausedCapsule(callback: Any): Boolean {
        val timeout = host.pauseTimeout ?: return false
        return pauseTimeout.retain { timeout.matches(it, callback) }.also { retained ->
            if (retained) diagnostics.note("Paused media capsule retained at native status bar timeout")
        }
    }

    private fun updatePauseOwner(binding: CapsuleLabels? = null, restore: Boolean = true) {
        val timeout = host.pauseTimeout ?: return
        val active = media
        val watch = session
        val controller = mediaController.get()
        val next = if (settings.enabled && settings.pauseBehavior == PauseBehavior.KEEP_CURRENT &&
            binding?.hasCustomContent == true && active != null &&
            controller != null && watch?.token == active.token && watch.isPaused) {
            NativeContract.PauseOwner(controller, active.key, active.token, active.owner)
        } else null
        pauseTimeout.update(next, restore)?.let { owner ->
            timeout.restore(owner) { report("Media pause timeout restore", it) }
        }
    }

    fun contentChanged(container: Any) {
        val currentRoot = root ?: return
        if (!settings.enabled || plugin?.belongs(container, currentRoot) != true) return
        labels?.takeIf { it.target.capsule.parent === container && currentMediaCardScene(it) == null }?.suspendViewport()
        // Add the sizing child before the OEM measures the new item and captures animation bounds.
        val refreshPending = main.hasCallbacks(refresh)
        if (refreshPending) main.removeCallbacks(refresh)
        protect("Capsule content") { refreshTarget(forceRender = refreshPending) }
    }

    private fun loadPreferences() {
        val appCtx = hostView?.context?.applicationContext ?: resolveAppContext() ?: return
        if (!settingsUpdateRegistered) {
            try {
                CapsuleSettingsUpdate.register(
                    appCtx,
                    modulePackage,
                    main,
                    apply = { next ->
                        receivedCommittedSettings = true
                        main.removeCallbacks(applyPreferences)
                        trace("preferences", "COMMIT_RECEIVED")
                        protect("Committed capsule settings") { applySettings(next) }
                    },
                    onLiveCommand = { cmd, offsetMs ->
                        protect("Live capsule command") {
                            when (cmd) {
                                CapsuleSettingsUpdate.CMD_SET_OFFSET -> session?.applyLyricOffset(offsetMs)
                                CapsuleSettingsUpdate.CMD_SEEK_TO -> session?.seekToLyric(offsetMs)
                                CapsuleSettingsUpdate.CMD_REFRESH_LYRICS -> session?.reloadOnlineLyricsAndTranslation(
                                    sourceChanged = true,
                                    translationChanged = false,
                                    forceRefresh = true,
                                )
                            }
                        }
                    },
                )
                settingsUpdateRegistered = true
            } catch (failure: Exception) {
                report("Register committed capsule settings", failure)
            }
        }
        if (preferences != null) return
        main.removeCallbacks(connect)
        try {
            val prefs = remotePreferences()
            prefs.registerOnSharedPreferenceChangeListener(onPreferences)
            preferences = prefs
            diagnostics.note("Remote preferences connected")
            if (!receivedCommittedSettings) readPreferences()
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            preferences?.unregisterOnSharedPreferenceChangeListener(onPreferences)
            preferences = null
            if (!receivedCommittedSettings) {
                stopObservation()
                settings = CapsuleSettings(enabled = false)
            }
            diagnostics.failure("Remote preferences attempt ${preferenceAttempts + 1}", failure)
            setStatus("Remote preferences unavailable")
            if (++preferenceAttempts <= 3) main.postDelayed(connect, 1200)
            return
        }
        if (!settings.enabled) setStatus("Lyrics disabled")
    }

    private fun startObservation() {
        val view = hostView ?: return
        if (!settings.enabled || observer?.isAlive == true) return
        observer = view.viewTreeObserver.takeIf { it.isAlive }?.also {
            it.addOnGlobalLayoutListener(globalLayout)
            it.addOnPreDrawListener(preDraw)
        }
    }

    private fun stopObservation(releaseMedia: Boolean = true) {
        main.removeCallbacks(refresh)
        layoutChanged = false
        if (releaseMedia) clearMediaRetry()
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(preDraw)
            it.removeOnGlobalLayoutListener(globalLayout)
        }
        observer = null
        if (releaseMedia) {
            vocalTracker.setActive(false)
            releaseSession()
            media = null
        }
        detachPlugin()
    }

    private fun detachPlugin() {
        mediaCardScene = null
        if (plugin != null) diagnostics.note("Native capsule plugin hooks detached, count=${pluginHooks.size}")
        clearLabels()
        centeredMedia.clear()
        pluginHooks.forEach { it.unhook() }
        pluginHooks = emptyList()
        viewportHookInstalled = false
        plugin = null
        root = null
    }

    private fun readMedia() {
        val controller = mediaController.get()
        val candidate = if (controller == null) {
            trace("media", "NO_CONTROLLER_CALLBACK")
            null
        } else {
            host.activeMedia(controller, dispatched) { trace("media", it) }
        }
        val previous = media
        if (candidate == null && previous != null && labels != null) {
            val now = SystemClock.uptimeMillis()
            val missingSince = mediaMissingSince ?: now.also { mediaMissingSince = it }
            val remaining = MEDIA_HANDOFF_MS - (now - missingSince)
            if (remaining > 0L) {
                if (!main.hasCallbacks(retryMedia)) main.postDelayed(retryMedia, remaining)
                return
            }
        }
        clearMediaRetry()
        if (candidate?.token != previous?.token || candidate?.owner != previous?.owner) {
            // A player handoff can reuse the same native card. Keep its sizing child attached
            // while switching sessions so the OEM does not measure a different width in between.
            val reuseLabels = candidate != null && candidate.key == previous?.key && labels?.let {
                plugin?.hasOwner(it.target.capsule, candidate.key) == true
            } == true
            releaseSession(keepLabels = reuseLabels)
            if (candidate != null && candidate.token != destroyedToken) destroyedToken = null
        }
        media = candidate
    }

    private fun clearMediaRetry() {
        main.removeCallbacks(retryMedia)
        mediaMissingSince = null
    }

    private fun ensureSessionForAodOrLockscreen() {
        if (!settings.enabled || session != null) return
        val appCtx = hostView?.context?.applicationContext ?: resolveAppContext() ?: return
        val candidate = media?.takeUnless { it.token == destroyedToken }
        if (candidate != null && settings.allowsApp(candidate.owner)) {
            val controller = runCatching { MediaController(appCtx, candidate.token) }.getOrNull()
            if (controller != null && controller.packageName == candidate.owner) {
                val watch = Session(candidate.token, controller)
                session = watch
                controller.registerCallback(watch, main)
                watch.metadataChanged(controller.metadata)
                watch.playbackChanged(controller.playbackState)
                return
            }
        }
        val msm = runCatching {
            appCtx.getSystemService(android.media.session.MediaSessionManager::class.java)
        }.getOrNull() ?: return
        val controllers = runCatching { msm.getActiveSessions(null) }.getOrNull().orEmpty()
        val chosen = controllers.firstOrNull { c ->
            c.sessionToken != destroyedToken &&
                settings.allowsApp(c.packageName) &&
                c.playbackState?.state == PlaybackState.STATE_PLAYING &&
                !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        } ?: controllers.firstOrNull { c ->
            c.sessionToken != destroyedToken &&
                settings.allowsApp(c.packageName) &&
                !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        } ?: return
        val watch = Session(chosen.sessionToken, chosen)
        session = watch
        chosen.registerCallback(watch, main)
        watch.metadataChanged(chosen.metadata)
        watch.playbackChanged(chosen.playbackState)
    }

    private fun postRefresh() {
        if (settings.enabled && !main.hasCallbacks(refresh)) main.post(refresh)
    }

    private fun refreshTarget(forceRender: Boolean) {
        layoutChanged = false
        if (!settings.enabled) return
        val view = hostView?.takeIf { it.isAttachedToWindow }
        val currentRoot = if (view == null) {
            trace("root", "HOST_NOT_ATTACHED")
            null
        } else {
            NativeContract.root(view) { trace("root", it) }
        }
        if (root !== currentRoot) {
            detachPlugin()
            root = currentRoot
            val loader = currentRoot?.javaClass?.classLoader
            if (currentRoot != null) trace("plugin loader", "present=${loader != null}, root=${currentRoot.javaClass.name}")
            if (currentRoot != null && loader != null && rejectedLoader.get() !== loader) {
                try {
                    val contract = NativeContract.Plugin(loader, currentRoot.resources) { feature, failure ->
                        report("Optional $feature", failure)
                    }
                    diagnostics.note("Required plugin classes, drawing method and 7 named resource IDs resolved")
                    pluginHooks = installPlugin(contract, this)
                    plugin = contract
                    Log.i(TAG, "Capsule plugin contract ready")
                    trace("plugin", "BOUND, animation_stabilizer=${contract.animation != null}")
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    rejectedLoader = WeakReference(loader)
                    trace("plugin", "REJECTED")
                    report("Plugin contract", failure)
                }
            }
        }
        if (currentRoot == null || plugin == null) {
            setStatus(if (currentRoot != null && rejectedLoader.get() === currentRoot.javaClass.classLoader) {
                "Native capsule plugin contract did not match"
            } else "Waiting for native capsule plugin")
            return
        }
        val candidate = media?.takeUnless { it.token == destroyedToken }
        if (candidate != null && !settings.allowsApp(candidate.owner)) {
            clearLabels()
            setStatus("App not in whitelist: ${candidate.owner}")
            return
        }
        val target = candidate?.let { active ->
            plugin?.findTarget(currentRoot, active.key, labels?.target) { trace("target", it) }
        }
        if (candidate == null || target == null) {
            clearLabels()
            ensureSessionForAodOrLockscreen()
            renderAodOnly()
            setStatus(if (candidate == null) "Waiting for native media notification" else "Native media capsule not visible")
            return
        }
        val newSession = session?.token != candidate.token
        if (newSession) {
            val controller = MediaController(currentRoot.context, candidate.token)
            if (controller.packageName != candidate.owner) {
                releaseSession()
                setStatus("Native media and session owner differ")
                return
            }
            val watch = Session(candidate.token, controller)
            session = watch
            val ownerVersion = runCatching {
                val info = currentRoot.context.packageManager.getPackageInfo(candidate.owner, 0)
                "${info.versionName} (${info.longVersionCode})"
            }.getOrDefault("unavailable")
            diagnostics.note("Media session bound, owner verified, player=${candidate.owner}, version=$ownerVersion")
            controller.registerCallback(watch, main)
            watch.metadataChanged(controller.metadata)
            watch.playbackChanged(controller.playbackState)
        }
        val newBinding = labels?.target != target
        if (newBinding) {
            clearLabels()
            if (target.capsule !in originalCapsuleMetrics) {
                val origWidth = target.capsule.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
                originalCapsuleMetrics[target.capsule] = origWidth to target.capsule.minimumWidth
            }
            labels = CapsuleLabels(target, originalCapsuleMetrics[target.capsule], vocalTracker::pacedPosition)
            Log.i(TAG, "Verified native media capsule label slots")
            fun node(view: View) = "${view.javaClass.name}(${view.width}x${view.height},shown=${view.isShown})"
            diagnostics.note("Native capsule bound: capsule=${node(target.capsule)}, titleFrame=${node(target.title.frame)}, title=${node(target.title.stock)}")
            diagnostics.note("Native capsule slots: translationFrame=${node(target.translation.frame)}, translation=${node(target.translation.stock)}, artwork=${node(target.artwork)}, waveform=${node(target.waveform)}")
        }
        if (forceRender || newSession || newBinding || renderedShown != target.capsule.isShown) {
            render()
        } else {
            labels?.maintainNativeSizing()
        }
    }

    private fun renderAodOnly() {
        val watch = session
        val timeline = watch?.timeline
        if (!isLockscreenBridgeEnabled() || watch == null || timeline == null) {
            aodOverlay.restoreNative()
            return
        }
        val presentation = LyricPresentation(timeline, settings)
        val clock = watch.playbackClock()
        val now = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        val position = clock.position(now)
        val index = if (watch.hasPlaybackPosition) presentation.indexAt(position) else -1
        val trackRows = { watch.trackInfo()?.rows }
        val frame = if (watch.hasPlaybackPosition) {
            presentation.introAt(position, trackRows) ?: presentation.frameAt(index, watch.duration, trackRows)
        } else null
        val current = presentation.lines.getOrNull(index)
        val first = current?.text?.trim()?.takeIf { it.isNotEmpty() }
        val second = current?.translation?.trim()?.takeIf { it.isNotEmpty() }
        val countdown = frame?.countdown

        val nextStart = presentation.nextBoundary(index)
        val wakeupTarget = when {
            countdown != null -> countdown.endMs
            nextStart != null -> nextStart
            else -> null
        }
        val nextDelay = if (clock.moving && wakeupTarget != null) clock.delayTo(position, wakeupTarget) else null

        updateAodOverlay(watch, timeline, index, first, second, countdown, position, nextDelay)

        val titleTv = aodTitleView.get()?.takeIf { it.isAttachedToWindow }
        if (titleTv != null && first != null && titleTv.text?.toString() != first) {
            titleTv.text = first
        }
        val artistTv = aodArtistView.get()?.takeIf { it.isAttachedToWindow }
        if (artistTv != null && second != null && artistTv.text?.toString() != second) {
            artistTv.text = second
        }

        val aodActive = aodOverlay.isActive || titleTv != null
        if (aodActive && clock.moving && wakeupTarget != null && nextDelay != null) {
            // If next line is longer than the steady wake-lock ceiling, wake once mid-way to refresh
            // DRAW_WAKE_LOCK cleanly without letting the panel drop to STATE_DOZE_SUSPEND.
            val alarmDelay = minOf(nextDelay, AodLyricOverlay.STEADY_DOZE_HOLD_MAX_MS - 3_000L)
            aodOverlay.scheduleDozeWakeup(alarmDelay, main) { protect("AOD lyric tick", ::render) }
            val planned = LyricBoundaryTick.plan(clock, wakeupTarget, now, uptime)
            setLyricTick(planned)
        } else {
            aodOverlay.cancelDozeWakeup()
        }
    }

    private fun render() {
        val binding = labels
        if (binding == null) {
            renderAodOnly()
            return
        }
        val currentRoot = root ?: run { renderAodOnly(); return }
        val candidate = media ?: return
        // Recheck ownership on every lyric boundary in case the OEM reused the view.
        if (plugin?.findTarget(currentRoot, candidate.key, binding.target) { trace("target", it) } != binding.target) {
            trace("render", "OWNER_OR_TARGET_CHANGED; restoring native content")
            clearLabels()
            renderAodOnly()
            return
        }
        val watch = session ?: return
        val shown = binding.target.capsule.isShown
        renderedShown = shown
        if (watch.isPaused && settings.pauseBehavior == PauseBehavior.RESTORE_NATIVE) {
            centeredMedia.remove(binding.target.capsule)
            updateInterludeStatusBarSpace(compact = false)
            aodOverlay.restoreNative()
            if (!binding.isCollapsingForPause && !binding.nativeWhilePaused) {
                binding.beginRestoreForPause()
            }
            setLyricTick(null)
            tintDrawFrame = null
            updatePauseOwner()
            setStatus("Using native media capsule while paused")
            return
        }
        val tintEnabled = settings.lyricTintEnabled
        val artwork = watch.artworkSource(candidate.artwork, binding.target.artwork)
        val curTrack = watch.aodSongTitle()
        if (artwork != null && !curTrack.isNullOrBlank()) {
            reportNowPlayingNow(
                title = curTrack,
                artist = watch.aodSongArtist().orEmpty(),
                source = if (watch.timeline != null) watch.currentLyricSource else "",
                wordTimed = watch.timeline != null && watch.isTrueWordSync,
                artwork = artwork,
                timeline = watch.timeline,
            )
        }
        val background = if (settings.artwork) watch.background(artwork, settings[CapsuleTuning.BLUR]) else null
        val ink = watch.tint.resolve(watch.tintArtwork.bitmap, background, settings,
            settings[CapsuleTuning.ISLAND_WIDTH] / 30f)
        val correlation = watch.tintCorrelation()
        if (tintEnabled) {
            trace("tint/source", "$correlation, ${watch.artworkObservation}")
            trace("tint/extraction", "$correlation, ${watch.tint.extractionDetail}")
            trace("tint/contrast", "$correlation, ${watch.tint.contrastDetail}")
            trace("tint/cache", "$correlation, ${watch.tint.cacheDetail}")
        }
        trace("tint/palette", "enabled=$tintEnabled, algorithm=${settings.lyricTintMode}, " +
            "$correlation, strength=${settings[CapsuleTuning.TINT_STRENGTH]}, source=${watch.tintArtwork.origin}, backgroundSource=${watch.artworkOrigin}, " +
            "background=${background != null}, extraction=${watch.tint.extraction}, result=${watch.tint.outcome}, ${ink.diagnosticColors()}")
        val presentation = watch.timeline?.let { LyricPresentation(it, settings) }
        if (presentation == null || presentation.lines.isEmpty()) {
            setLyricTick(null)
            aodOverlay.restoreNative()
            val trackInfo = if (settings.noLyricsMode == NoLyricsMode.TRACK) watch.trackInfo() else null
            renderFrame(binding, trackInfo, "NO_TIMELINE", background, ink)
            trace("render", "NO_TIMELINE, trackInfo=${trackInfo != null}, artwork=${background != null}, shown=${binding.target.capsule.isShown}, size=${binding.target.capsule.width}x${binding.target.capsule.height}")
            setStatus(if (trackInfo != null) "Showing track info without usable lyric timing"
                else "Waiting for timed lyrics from the active session")
            return
        }
        val clock = watch.playbackClock()
        val now = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        val position = clock.position(now)
        val index = if (watch.hasPlaybackPosition) presentation.indexAt(position) else -1
        val line = presentation.lines.getOrNull(index)?.takeIf { it.text.isNotBlank() }
        val next = presentation.nextBoundary(index)
        val frame = if (watch.hasPlaybackPosition) {
            val trackRows = { watch.trackInfo()?.rows }
            presentation.introAt(position, trackRows) ?: presentation.frameAt(index, watch.duration, trackRows)
        } else null
        if (watch.lastLoggedLine != index) {
            watch.lastLoggedLine = index
            val mode = index.takeIf { it >= 0 }?.let(presentation::switchMode)
            diagnostics.note("[lyric schedule] index=$index/${presentation.lines.size}, positionMs=$position, range=${line?.startMs}..${line?.endMs}, scrollEnd=${frame?.endMs}, displayStart=${frame?.displayStartMs}, next=$next, mode=$mode, secondRow=${frame?.rows?.second != null}, translated=${!line?.translation.isNullOrBlank()}, shown=${binding.target.capsule.isShown}")
        }
        val useVocalPacing = settings.enabled && settings.interpolateWordLyrics &&
            !watch.isTrueWordSync && !watch.isPaused && shown && frame != null && frame.countdown == null
        vocalTracker.setActive(useVocalPacing)
        if (frame != null) {
            val phase = when {
                frame.isIntro -> "INTRO_DOTS"
                frame.countdown != null -> "INTERLUDE_DOTS"
                else -> "SYNCED"
            }
            renderFrame(binding, frame, phase, background, ink, clock)
            setStatus(if (frame.countdown != null) "Rendering lyric countdown" else "Rendering lyrics in native media capsule")
        } else {
            val introInfo = if (index < 0 && settings.noLyricsMode == NoLyricsMode.TRACK) watch.trackInfo() else null
            renderFrame(binding, introInfo, "INTRO_OR_GAP", background, ink)
            setStatus("Waiting for next lyric boundary")
        }
        val aodCountdown = frame?.countdown
        val aodWakeTarget = when {
            aodCountdown != null -> aodCountdown.endMs
            next != null -> next
            else -> null
        }
        val aodNextDelay = if (clock.moving && aodWakeTarget != null) clock.delayTo(position, aodWakeTarget) else null
        updateAodOverlay(
            watch = watch,
            timeline = watch.timeline,
            index = index,
            mainText = line?.text,
            translationText = line?.translation,
            countdown = aodCountdown,
            positionMs = position,
            nextLineDelayMs = aodNextDelay,
        )
        val aodActive = aodOverlay.isActive
        if (aodActive && clock.moving && aodNextDelay != null) {
            val alarmDelay = minOf(aodNextDelay, AodLyricOverlay.STEADY_DOZE_HOLD_MAX_MS - 3_000L)
            aodOverlay.scheduleDozeWakeup(alarmDelay, main) { protect("AOD lyric tick", ::render) }
        } else {
            aodOverlay.cancelDozeWakeup()
        }
        val planned = if ((shown || aodActive) && next != null) LyricBoundaryTick.plan(clock, next, now, uptime) else null
        setLyricTick(planned)
        trace("scheduler", when {
            planned != null -> "NEXT_LINE index=$index, targetMs=$next"
            !shown || next == null -> "NO_NEXT_OR_HIDDEN index=$index, shown=$shown"
            else -> "NOT_MOVING index=$index"
        })
    }

    private fun setLyricTick(planned: LyricBoundaryTick?) {
        val previous = scheduledTick
        if (planned == previous || planned?.sameDeadline(previous) == true) return
        if (previous != null) main.removeCallbacks(tick)
        scheduledTick = planned
        if (planned != null) main.postAtTime(tick, planned.uptimeMs)
    }

    private var lastTintDrawFrame: String? = null
    private var lastTintDrawResult: String? = null

    private fun renderFrame(
        binding: CapsuleLabels, frame: LyricFrame?, phase: String, background: Bitmap?, ink: LyricInk,
        clock: PlaybackClock? = null,
    ) {
        binding.render(frame, settings, background, clock, ink, trackIdentity = session?.trackIdentity)
        updateAodLyricViews(frame)
        if (!binding.shouldReserveCompactStatusBarSpace) updateInterludeStatusBarSpace(compact = false)
        updatePauseOwner(binding)
        val kind = when {
            frame == null -> "NO_TEXT"
            frame.isTrackInfo -> "TRACK_INFO"
            frame.isIntro -> "INTRO_DOTS"
            frame.countdown != null -> "INTERLUDE_DOTS"
            else -> "LYRICS"
        }
        val detail = "${session?.tintCorrelation()}, phase=$phase, kind=$kind, secondRow=${frame?.rows?.second != null}, opacity=${settings[CapsuleTuning.FIRST_ROW_OPACITY]}/${settings[CapsuleTuning.SECOND_ROW_OPACITY]}, ${ink.diagnosticColors()}"
        tintDrawFrame = detail
        trace("tint/frame", detail)
    }

    private fun LyricInk.diagnosticColors(): String {
        val rows = "first=#${first.toUInt().toString(16)}, second=#${second.toUInt().toString(16)}"
        val stops = gradient ?: return rows
        return "$rows, gradient=#${stops.start.toUInt().toString(16)}..#${stops.end.toUInt().toString(16)}"
    }

    private fun clearLabels() {
        updateInterludeStatusBarSpace(compact = false)
        updatePauseOwner()
        setLyricTick(null)
        renderedShown = null
        tintDrawFrame = null
        lastTintDrawFrame = null
        lastTintDrawResult = null
        val binding = labels
        labels = null
        if (binding != null) {
            binding.close()
            diagnostics.note("Native capsule content restored and labels released")
        }
    }

    private fun releaseSession(keepLabels: Boolean = false) {
        updatePauseOwner()
        mediaCardScene = null
        setLyricTick(null)
        val current = session
        session = null
        current?.cancelMetadataExpiry()
        if (keepLabels) {
            updateInterludeStatusBarSpace(compact = false)
            labels?.suspendViewport()
        } else clearLabels()
        current?.controller?.unregisterCallback(current)
    }

    private fun onMain(step: String, block: () -> Unit) {
        if (Looper.myLooper() == main.looper) protect(step, block) else main.post { protect(step, block) }
    }

    private fun protect(step: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            report(step, failure)
            try { releaseSession() } catch (cleanup: Exception) { report("Capsule cleanup", cleanup) }
            setStatus("$step unavailable (${failure.javaClass.simpleName})")
        }
    }

    private fun trace(stage: String, detail: String) {
        if (stageMessages.put(stage, detail) == detail) return
        val message = "[$stage] $detail"
        Log.i(TAG, message)
        diagnostics.note(message)
    }

    private fun report(step: String, failure: Throwable) {
        reportFailure(step, failure)
        diagnostics.failure(step, failure)
    }

    private fun setStatus(message: String) {
        if (status == message) return
        status = message
        Log.i(TAG, message)
        diagnostics.note(message)
    }

    private inner class Session(val token: MediaSession.Token, val controller: MediaController) : MediaController.Callback() {
        private val playback = CapsulePlayback()
        private var trackSequence = 0L
        var trackIdentity = Any()
            private set
        fun tintCorrelation() = "pid=${android.os.Process.myPid()}, session=${System.identityHashCode(this)}, track=$trackSequence, calculation=${tint.calculationId}"
        val hasPlaybackPosition: Boolean get() = playback.hasPosition
        var timeline: Timeline? = null
            private set
        var isTrueWordSync: Boolean = false
            private set
        var currentLyricSource: String = ""
            private set
        var lyricOffsetMs: Long = 0L
            private set
        private var offsetInitializedForTrack: Boolean = false
        var duration = 0L
            private set
        private var metadata: MediaMetadata? = null
        private var track: Track? = null
        private var raw: String? = null
        private var previousTrackPayload: String? = null
        private var lastTrackPayload: String? = null
        private var metadataArtwork: Bitmap? = null
        private var nativeArtwork: Bitmap? = null
        private var nativeArtworkGeneration = 0
        private var nativeArtworkIsDirectBitmap = false
        private var previousNativeArtwork: Bitmap? = null
        private var selectedArtwork: Bitmap? = null
        private var selectedArtworkGeneration = 0
        private var observedMediaTint: Bitmap? = null
        private var observedMediaTintGeneration = 0
        private var bitmap: Bitmap? = null
        private var waitsForNativeArtwork = false
        var artworkOrigin = "NONE"
            private set
        var artworkObservation = "not observed"
            private set
        var tintArtwork = TintArtwork<Bitmap>(null, TintArtworkOrigin.NOT_REQUESTED)
            private set
        val tint = ArtworkLyricTint()
        private var icon: Icon? = null
        private var iconArtwork: Bitmap? = null
        private var blurred: Bitmap? = null
        private var blurStrength = -1
        private var previousArtwork: Bitmap? = null
        private var previousArtworkIcon: Icon? = null
        private var trackChanged = false
        private var missingTrack = false
        private var trackInfoAvailable = false
        private var lastPlaybackState: Int? = null
        val isPaused: Boolean get() = lastPlaybackState == PlaybackState.STATE_PAUSED
        private var lyricStatus: String? = null
        var lastLoggedLine: Int? = null
        private val expireMetadata = Runnable {
            protect("Media metadata expiry") {
                if (session === this) {
                    timeline = null
                    raw = null
                    if (missingTrack) trackInfoAvailable = false
                    diagnostics.note("Media metadata grace period expired")
                    render()
                }
            }
        }

        private var onlineFetchSeq = -1L
        private var onlineTranslateSeq = -1L
        private var pendingOnlineFetch: Runnable? = null

        private fun cancelOnlineFetch() {
            pendingOnlineFetch?.let(main::removeCallbacks)
            pendingOnlineFetch = null
        }

        private fun resolveNativePayload(): String? =
            readNativeLyricInfo(metadata)?.takeUnless { it == previousTrackPayload }
                ?: readNativeLyricInfo(controller.metadata)?.takeUnless { it == previousTrackPayload }
                ?: lastTrackPayload?.takeUnless { it == previousTrackPayload }

        private fun parsePlayerPayload(
            nativePayload: String,
            targetTrack: Track,
            isTrackChanged: Boolean,
            trackDurationMs: Long,
        ): Pair<LyricPayload.Result, Pair<String, Boolean>> {
            val initial = LyricPayload.inspect(nativePayload, targetTrack.title, isTrackChanged, targetTrack.artist)
            val initialTimeline = initial.timeline ?: return initial to (nativePayload to false)
            val hasTrueWords = initialTimeline.lines.any { it.words.isNotEmpty() }
            if (hasTrueWords) {
                return initial to (nativePayload to true)
            }
            val adjusted = shop.terramoor.github.io.lyrics.OnlineLyricFetcher.applyInterpolationMode(
                nativePayload,
                isTrueWordSync = false,
                interpolateWords = settings.interpolateWordLyrics,
                durationMs = trackDurationMs,
            )
            if (adjusted == nativePayload) {
                return initial to (nativePayload to false)
            }
            val reparsed = LyricPayload.inspect(adjusted, targetTrack.title, isTrackChanged, targetTrack.artist)
            return if (reparsed.timeline != null) {
                reparsed to (adjusted to false)
            } else {
                initial to (nativePayload to false)
            }
        }

        fun applyLyricOffset(offsetMs: Long) {
            val clamped = offsetMs.coerceIn(LyricBridgeProvider.MIN_OFFSET_MS, LyricBridgeProvider.MAX_OFFSET_MS)
            offsetInitializedForTrack = true
            if (lyricOffsetMs == clamped) return
            lyricOffsetMs = clamped
            lastLoggedLine = null
            render()
        }

        fun seekToLyric(lineStartMs: Long) {
            val targetMs = (lineStartMs - lyricOffsetMs).coerceAtLeast(0L)
            runCatching { controller.transportControls.seekTo(targetMs) }
        }

        fun initTrackOffsetIfNeeded(title: String, artist: String, savedOffsetMs: Long) {
            if (offsetInitializedForTrack) return
            if (track?.title.orEmpty() != title || track?.artist.orEmpty() != artist) return
            offsetInitializedForTrack = true
            val clamped = savedOffsetMs.coerceIn(LyricBridgeProvider.MIN_OFFSET_MS, LyricBridgeProvider.MAX_OFFSET_MS)
            if (lyricOffsetMs != clamped) {
                lyricOffsetMs = clamped
                lastLoggedLine = null
                render()
            }
        }

        fun reloadOnlineLyricsAndTranslation(
            sourceChanged: Boolean,
            translationChanged: Boolean,
            forceRefresh: Boolean = false,
        ) {
            val currentTrack = track ?: return
            if (sourceChanged) {
                cancelOnlineFetch()
                onlineFetchSeq = -1L
                onlineTranslateSeq = -1L
                raw = null
                timeline = null
                currentLyricSource = ""
                isTrueWordSync = false
                lastLoggedLine = null
                val mode = settings.lyricSourceMode
                if (mode == shop.terramoor.github.io.settings.LyricSourceMode.EXTERNAL_ONLY ||
                    mode == shop.terramoor.github.io.settings.LyricSourceMode.AUTO) {
                    val nativePayload = resolveNativePayload()
                    val parsedPair = nativePayload?.let { parsePlayerPayload(it, currentTrack, isTrackChanged = false, trackDurationMs = duration) }
                    val nativeTimeline = parsedPair?.first?.timeline
                    if (nativeTimeline != null) {
                        val (effectivePayload, trueWords) = parsedPair.second
                        lastTrackPayload = nativePayload
                        raw = effectivePayload
                        timeline = nativeTimeline
                        currentLyricSource = "player"
                        isTrueWordSync = trueWords
                        reportNowPlayingNow(
                            title = currentTrack.title.orEmpty(),
                            artist = currentTrack.artist.orEmpty(),
                            source = "player",
                            wordTimed = trueWords,
                            artwork = aodArtwork(),
                            timeline = nativeTimeline,
                        )
                        requestOnlineTranslation(nativeTimeline, trackSequence)
                        render()
                        return
                    }
                    if (mode == shop.terramoor.github.io.settings.LyricSourceMode.EXTERNAL_ONLY) {
                        reportNowPlayingNow(
                            title = currentTrack.title.orEmpty(),
                            artist = currentTrack.artist.orEmpty(),
                            source = "",
                            wordTimed = false,
                            artwork = aodArtwork(),
                        )
                        render()
                        return
                    }
                }
                reportNowPlayingNow(
                    title = currentTrack.title.orEmpty(),
                    artist = currentTrack.artist.orEmpty(),
                    source = "",
                    wordTimed = false,
                    artwork = aodArtwork(),
                )
                scheduleOnlineFallback(currentTrack, trackSequence, duration, forceRefresh = forceRefresh)
                render()
                return
            }
            if (translationChanged) {
                onlineTranslateSeq = -1L
                val baseRaw = raw
                if (!baseRaw.isNullOrBlank()) {
                    val reparsed = LyricPayload.inspect(baseRaw, currentTrack.title, false, currentTrack.artist).timeline
                    if (reparsed != null) {
                        timeline = reparsed
                        lastLoggedLine = null
                        render()
                        requestOnlineTranslation(reparsed, trackSequence)
                    }
                }
            }
        }

        private fun scheduleOnlineFallback(
            targetTrack: Track,
            seq: Long,
            trackDurationMs: Long,
            forceRefresh: Boolean = false,
        ) {
            val sourceMode = settings.lyricSourceMode
            if (sourceMode == shop.terramoor.github.io.settings.LyricSourceMode.EXTERNAL_ONLY) return
            if (!forceRefresh && onlineFetchSeq == seq) return
            val title = targetTrack.title?.trim()?.takeIf { it.isNotEmpty() } ?: return
            cancelOnlineFetch()
            val task = Runnable {
                pendingOnlineFetch = null
                if (session !== this || trackSequence != seq || timeline != null) return@Runnable
                val ctx = hostView?.context?.applicationContext ?: resolveAppContext() ?: return@Runnable
                onlineFetchSeq = seq
                val uri = android.net.Uri.parse("content://$modulePackage${LyricBridgeProvider.AUTHORITY_SUFFIX}")
                val args = android.os.Bundle().apply {
                    putString(LyricBridgeProvider.EXTRA_TITLE, title)
                    putString(LyricBridgeProvider.EXTRA_ARTIST, targetTrack.artist)
                    putLong(LyricBridgeProvider.EXTRA_DURATION_MS, trackDurationMs)
                    putString(LyricBridgeProvider.EXTRA_SOURCE_MODE, sourceMode.name)
                    putBoolean(LyricBridgeProvider.EXTRA_INTERPOLATE_WORDS, settings.interpolateWordLyrics)
                    putBoolean(LyricBridgeProvider.EXTRA_AUTO_CLEAR_CACHE, settings.autoClearCache)
                    putBoolean(LyricBridgeProvider.EXTRA_FORCE_REFRESH, forceRefresh)
                }
                onlineExecutor.execute {
                    val reply = try {
                        ctx.contentResolver.call(uri, LyricBridgeProvider.METHOD_FETCH_LYRICS, null, args)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Online lyrics fetch IPC failed: ${e.message}")
                        null
                    }
                    val payload = reply?.getString(LyricBridgeProvider.EXTRA_PAYLOAD)?.takeIf { it.isNotBlank() } ?: return@execute
                    val source = reply.getString(LyricBridgeProvider.EXTRA_SOURCE).orEmpty()
                    val isTrueWordSync = reply.getBoolean(LyricBridgeProvider.EXTRA_WORD_TIMED, false)
                    main.post {
                        if (session !== this || trackSequence != seq || timeline != null) return@post
                        val parsed = LyricPayload.inspect(payload, targetTrack.title, false, targetTrack.artist)
                        val fetchedTimeline = parsed.timeline ?: return@post
                        timeline = fetchedTimeline
                        this.isTrueWordSync = isTrueWordSync
                        currentLyricSource = source
                        raw = payload
                        storeBridgedPayload(targetTrack.title, payload)
                        runCatching { org.json.JSONObject(payload).optString("lyric") }
                            .getOrNull()?.takeIf { it.isNotBlank() }?.let { lrcText ->
                                injectSeedlingLyrics(targetTrack.title, targetTrack.artist, lrcText)
                            }
                        lastLoggedLine = null
                        val wordLines = fetchedTimeline.lines.count { it.words.isNotEmpty() }
                        val msg = "[lyrics] ONLINE($source), lines=${fetchedTimeline.lines.size}, wordLines=$wordLines, trueWord=$isTrueWordSync, translated=${fetchedTimeline.lines.count { !it.translation.isNullOrBlank() }}"
                        logLyrics(msg)
                        Log.i(TAG, msg)
                        reportNowPlayingNow(
                            title = targetTrack.title.orEmpty(),
                            artist = targetTrack.artist.orEmpty(),
                            source = source,
                            wordTimed = isTrueWordSync,
                            artwork = aodArtwork(),
                            timeline = fetchedTimeline,
                        )
                        render()
                        requestOnlineTranslation(fetchedTimeline, seq)
                    }
                }
            }
            pendingOnlineFetch = task
            main.postDelayed(task, 350L)
        }

        private fun requestOnlineTranslation(current: Timeline, seq: Long) {
            if (!settings.autoTranslateVi) return
            if (onlineTranslateSeq == seq) return
            if (current.lines.isEmpty() || current.lines.any { !it.translation.isNullOrBlank() }) return
            val ctx = hostView?.context?.applicationContext ?: resolveAppContext() ?: return
            onlineTranslateSeq = seq
            val lineTexts = ArrayList<String>(current.lines.size).apply {
                current.lines.forEach { add(it.text) }
            }
            val uri = android.net.Uri.parse("content://$modulePackage${LyricBridgeProvider.AUTHORITY_SUFFIX}")
            val targetLang = settings.translateTargetLang.resolveCode()
            val args = android.os.Bundle().apply {
                putStringArrayList(LyricBridgeProvider.EXTRA_LINES, lineTexts)
                putString(LyricBridgeProvider.EXTRA_TARGET_LANG, targetLang)
            }
            onlineExecutor.execute {
                val reply = try {
                    ctx.contentResolver.call(uri, LyricBridgeProvider.METHOD_TRANSLATE_LINES, null, args)
                } catch (e: Throwable) {
                    Log.w(TAG, "Online translation IPC failed: ${e.message}")
                    null
                }
                val translated = reply?.getStringArrayList(LyricBridgeProvider.EXTRA_TRANSLATIONS) ?: return@execute
                if (translated.isEmpty()) return@execute
                main.post {
                    if (session !== this || trackSequence != seq) return@post
                    val activeTimeline = timeline ?: return@post
                    if (activeTimeline.lines.size != translated.size) return@post
                    val enriched = activeTimeline.withTranslations(translated)
                    timeline = enriched
                    // Also update bridged payload with translationLyric for Lockscreen / AOD
                    val currentRaw = raw
                    if (!currentRaw.isNullOrBlank()) {
                        runCatching {
                            val obj = org.json.JSONObject(currentRaw)
                            val trLrc = buildTranslationLrc(enriched)
                            if (trLrc.isNotBlank()) obj.put("translationLyric", trLrc)
                            storeBridgedPayload(track?.title, obj.toString())
                        }
                    }
                    lastLoggedLine = null
                    val count = enriched.lines.count { !it.translation.isNullOrBlank() }
                    val msg = "[lyrics] TRANSLATED(vi), lines=$count/${activeTimeline.lines.size}"
                    logLyrics(msg)
                    Log.i(TAG, msg)
                    reportNowPlayingNow(
                        title = track?.title.orEmpty(),
                        artist = track?.artist.orEmpty(),
                        source = currentLyricSource,
                        wordTimed = isTrueWordSync,
                        artwork = aodArtwork(),
                        timeline = enriched,
                    )
                    render()
                }
            }
        }

        fun cancelMetadataExpiry() {
            main.removeCallbacks(expireMetadata)
            cancelOnlineFetch()
        }

        private fun markMetadataMissing(identityMissing: Boolean = false) {
            missingTrack = identityMissing
            if ((timeline != null || identityMissing && trackInfoAvailable) && !main.hasCallbacks(expireMetadata)) {
                main.postDelayed(expireMetadata, METADATA_GRACE_MS)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) = protect("Media metadata") {
            if (session === this) {
                // A payload or artwork update is not a playback-state update. Pull a fresh
                // snapshot only on a track handoff, when playback may have arrived first.
                if (metadataChanged(metadata)) playbackChanged(controller.playbackState)
                render()
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = protect("Playback state") {
            if (session === this) {
                playbackChanged(state)
                if (labels == null) postRefresh() else render()
            }
        }

        override fun onSessionDestroyed() = protect("Session destruction") {
            if (session === this) {
                destroyedToken = token
                diagnostics.note("Media session destroyed")
                releaseSession()
                postRefresh()
            }
        }

        fun playbackChanged(state: PlaybackState?) {
            val now = SystemClock.elapsedRealtime()
            val expectedPosition = playback.clock(duration).position(now)
            if (!playback.update(state?.state, state?.position ?: -1L, state?.lastPositionUpdateTime ?: 0L,
                    state?.playbackSpeed ?: 0f, duration, now)) return
            val seek = state != null && lastPlaybackState == state.state && state.position >= 0L &&
                kotlin.math.abs(state.position - expectedPosition) > 1500L
            if (lastPlaybackState != state?.state || seek) {
                lastPlaybackState = state?.state
                if (state?.state == PlaybackState.STATE_PLAYING) updatePauseOwner(restore = false)
                diagnostics.note("Playback state=${state?.state}, positionMs=${state?.position}, speed=${state?.playbackSpeed}, seek=$seek, expectedMs=$expectedPosition, durationMs=$duration")
            }
        }

        fun playbackClock(): PlaybackClock {
            val base = playback.clock(duration)
            return if (lyricOffsetMs == 0L) base
            else base.copy(basePositionMs = (base.basePositionMs + lyricOffsetMs).coerceAtLeast(0L))
        }

        fun aodSongTitle(): String? = track?.title?.trim()?.takeIf { it.isNotEmpty() }

        fun aodSongArtist(): String? = track?.artist?.trim()?.takeIf { it.isNotEmpty() }

        fun aodArtwork(): Bitmap? {
            (selectedArtwork ?: metadataArtwork ?: nativeArtwork ?: iconArtwork)
                ?.takeUnless { it.isRecycled }
                ?.let { return it }
            val data = metadata ?: return null
            return sequenceOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
            ).mapNotNull { data.getBitmap(it) }
                .firstOrNull { !it.isRecycled && it !== previousArtwork }
        }

        fun metadataChanged(data: MediaMetadata?): Boolean {
            if (data == null) {
                logLyrics("Media metadata missing")
                markMetadataMissing(identityMissing = true)
                return false
            }
            val nextTrack = Track(data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                data.getString(MediaMetadata.METADATA_KEY_TITLE), data.getString(MediaMetadata.METADATA_KEY_ARTIST))
            if (nextTrack.id.isNullOrBlank() && nextTrack.title.isNullOrBlank()) {
                logLyrics("Media metadata has no track identity")
                markMetadataMissing(identityMissing = true)
                return false
            }
            missingTrack = false
            val nextPayload = readNativeLyricInfo(data)
            val changed = track != nextTrack
            if (changed) {
                tintArtworkSelection.onTrack(token to nextTrack)
                trackSequence++
                trackIdentity = Any()
                lyricStatus = null
                lastLoggedLine = null
                diagnostics.note("Track metadata changed, previous track present=${track != null}")
                cancelMetadataExpiry()
                if (track != null) {
                    previousTrackPayload = lastTrackPayload ?: previousTrackPayload
                    lastTrackPayload = null
                    trackChanged = true
                    playback.newTrack()
                    // The OEM may still be displaying the old notification artwork during a handoff.
                    previousArtwork = metadataArtwork
                    previousArtworkIcon = icon
                    previousNativeArtwork = nativeArtwork
                    clearArtworkCache()
                }
                raw = null
                timeline = null
                currentLyricSource = ""
                isTrueWordSync = false
                lyricOffsetMs = 0L
                offsetInitializedForTrack = false
            }
            val nextDuration = data.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
                ?: if (changed) 0L else duration
            val allowPlayerSource = settings.lyricSourceMode == shop.terramoor.github.io.settings.LyricSourceMode.AUTO ||
                settings.lyricSourceMode == shop.terramoor.github.io.settings.LyricSourceMode.EXTERNAL_ONLY
            when {
                nextPayload == null || nextPayload == previousTrackPayload -> {
                    logLyrics(if (nextPayload == null) "No lyricInfo in metadata" else "Waiting for new track lyricInfo")
                    if (changed || timeline == null) markMetadataMissing()
                }
                !allowPlayerSource -> {
                    // Cache valid player payload in case user switches back to Trình phát / Tự động later.
                    if (lastTrackPayload != nextPayload) {
                        val check = LyricPayload.inspect(nextPayload, nextTrack.title, trackChanged, nextTrack.artist)
                        if (check.timeline != null) lastTrackPayload = nextPayload
                    }
                }
                else -> {
                    cancelMetadataExpiry()
                    if (lastTrackPayload != nextPayload || timeline == null) {
                        lastLoggedLine = null
                        val (parsed, meta) = parsePlayerPayload(nextPayload, nextTrack, trackChanged, nextDuration)
                        val (effectivePayload, trueWords) = meta
                        raw = effectivePayload
                        timeline = parsed.timeline
                        val lines = timeline?.lines
                        if (lines != null) {
                            lastTrackPayload = nextPayload
                            currentLyricSource = "player"
                            isTrueWordSync = trueWords
                        }
                        val outcome = if (lines == null) {
                            "[lyrics] ${parsed.outcome}, chars=${nextPayload.length}, LRC=${parsed.hasLrc}, TTML=${parsed.hasTtml}"
                        } else {
                            "[lyrics] PARSED(player), chars=${nextPayload.length}, lines=${lines.size}, wordLines=${lines.count { it.words.isNotEmpty() }}, trueWord=$trueWords, translated=${lines.count { !it.translation.isNullOrBlank() }}, LRC=${parsed.hasLrc}, TTML=${parsed.hasTtml}"
                        }
                        logLyrics(outcome)
                    }
                }
            }
            metadata = data
            track = nextTrack
            trackInfoAvailable = true
            duration = nextDuration
            val currentTimeline = timeline
            reportNowPlayingNow(
                title = nextTrack.title.orEmpty(),
                artist = nextTrack.artist.orEmpty(),
                source = if (currentTimeline != null) currentLyricSource else "",
                wordTimed = currentTimeline != null && isTrueWordSync,
                artwork = aodArtwork(),
                timeline = currentTimeline,
            )
            if (currentTimeline == null) {
                scheduleOnlineFallback(nextTrack, trackSequence, duration)
            } else {
                cancelOnlineFetch()
                requestOnlineTranslation(currentTimeline, trackSequence)
            }
            return changed
        }

        private fun logLyrics(message: String) {
            if (lyricStatus == message) return
            lyricStatus = message
            diagnostics.note(message)
        }

        fun trackInfo(): LyricFrame? {
            if (!trackInfoAvailable) return null
            val name = track?.title?.trim()?.take(512)?.takeIf { it.isNotEmpty() } ?: return null
            val byline = track?.artist?.trim()?.take(512)?.takeIf { it.isNotEmpty() }
            return LyricFrame.trackInfo(name, byline)
        }

        fun background(source: Bitmap?, strength: Int): Bitmap? {
            if (blurStrength != strength) {
                blurred = source?.let { blurArtwork(it, strength) }
                blurStrength = strength
            }
            return blurred
        }

        private fun clearArtworkCache() {
            metadataArtwork = null
            nativeArtwork = null
            nativeArtworkGeneration = 0
            nativeArtworkIsDirectBitmap = false
            selectedArtwork = null
            selectedArtworkGeneration = 0
            observedMediaTint = null
            observedMediaTintGeneration = 0
            icon = null
            iconArtwork = null
            bitmap = null
            blurred = null
            blurStrength = -1
        }

        fun artworkChanged(cover: View): Boolean {
            val drawable = (cover as? ImageView)?.drawable
            val currentNative = artworkBitmap(drawable)
            val currentTint = if (settings.lyricTintEnabled) mediaTintArtwork() else null
            val watchNative = waitsForNativeArtwork || settings.lyricTintEnabled
            return (watchNative && (currentNative !== nativeArtwork ||
                (currentNative?.generationId ?: 0) != nativeArtworkGeneration ||
                (drawable is BitmapDrawable) != nativeArtworkIsDirectBitmap)) ||
                currentTint !== observedMediaTint || (currentTint?.generationId ?: 0) != observedMediaTintGeneration ||
                (selectedArtwork?.takeUnless { it.isRecycled }?.generationId ?: 0) != selectedArtworkGeneration
        }

        private fun mediaTintArtwork(): Bitmap? {
            val data = metadata ?: return null
            return sequenceOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART)
                .mapNotNull { data.getBitmap(it) }
                .firstOrNull { !it.isRecycled && it !== previousArtwork }
        }

        fun artworkSource(fallback: Icon?, cover: View): Bitmap? {
            val drawable = (cover as? ImageView)?.drawable
            nativeArtwork = artworkBitmap(drawable)
            nativeArtworkGeneration = nativeArtwork?.generationId ?: 0
            nativeArtworkIsDirectBitmap = drawable is BitmapDrawable
            observedMediaTint = if (settings.lyricTintEnabled) mediaTintArtwork() else null
            observedMediaTintGeneration = observedMediaTint?.generationId ?: 0
            tintArtwork = tintArtworkSelection.select(
                settings.lyricTintEnabled, observedMediaTint, nativeArtwork.takeIf { nativeArtworkIsDirectBitmap },
                nativeArtworkGeneration, trackIdentified = track != null && !missingTrack,
            )
            if (!settings.lyricTintEnabled && !settings.artwork) return null

            val data = metadata
            val current = data?.let {
                fun currentBitmap(key: String) = it.getBitmap(key)?.takeUnless { image ->
                    image.isRecycled || image === previousArtwork
                }
                currentBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: currentBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: currentBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            val currentIcon = fallback?.takeUnless { it === previousArtworkIcon }
            if (icon !== currentIcon) {
                icon = currentIcon
                iconArtwork = null
            }
            if (current == null && iconArtwork == null && currentIcon != null &&
                (currentIcon.type == Icon.TYPE_BITMAP || currentIcon.type == Icon.TYPE_ADAPTIVE_BITMAP)) {
                val context = hostView?.context
                if (context != null) iconArtwork = artworkBitmap(currentIcon.loadDrawable(context))
            }
            metadataArtwork = current
            val iconBitmap = iconArtwork?.takeUnless { it.isRecycled }
            waitsForNativeArtwork = cover is ImageView && current == null && iconBitmap == null
            // The verified slot may still carry the preceding track's cover while the OEM updates it.
            val nativeBitmap = nativeArtwork?.takeUnless { it === previousNativeArtwork }
            val source = current ?: iconBitmap ?: nativeBitmap
            artworkOrigin = when {
                current != null -> "MEDIA_METADATA"
                iconBitmap != null -> "NOTIFICATION_BITMAP"
                nativeBitmap != null -> "NATIVE_COVER"
                else -> "NONE"
            }
            artworkObservation = "origin=$artworkOrigin, metadataBitmap=${current != null}, " +
                "notificationType=${fallback?.type}, notificationBitmap=${iconBitmap != null}, " +
                "nativeView=${cover.javaClass.simpleName}, drawable=${drawable?.javaClass?.simpleName}, nativeBitmap=${nativeBitmap != null}, " +
                "priorNotificationIgnored=${fallback != null && currentIcon == null}, " +
                "priorNativeIgnored=${nativeArtwork != null && nativeBitmap == null}, " +
                "tintAlbumArtReady=${observedMediaTint != null}, tintSource=${tintArtwork.origin}, " +
                "waitingForNative=$waitsForNativeArtwork, selected=${source?.width}x${source?.height}, config=${source?.config}, " +
                "identity=${source?.let(System::identityHashCode)}, generation=${source?.takeUnless { it.isRecycled }?.generationId}"
            trace("tint/metadata", "${tintCorrelation()}, albumArt=${data?.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART)}, art=${data?.containsKey(MediaMetadata.METADATA_KEY_ART)}, displayIcon=${data?.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON)}, selectedCacheHit=${selectedArtwork === source && bitmap?.isRecycled != true}")
            val sourceGeneration = source?.generationId ?: 0
            if (selectedArtwork === source && selectedArtworkGeneration == sourceGeneration && bitmap?.isRecycled != true) return bitmap
            selectedArtwork = source
            selectedArtworkGeneration = sourceGeneration
            blurred = null
            blurStrength = -1
            bitmap = source?.let {
                val longest = maxOf(it.width, it.height)
                if (longest <= 128) it else Bitmap.createScaledBitmap(it,
                    (it.width * 128f / longest).roundToInt().coerceAtLeast(1),
                    (it.height * 128f / longest).roundToInt().coerceAtLeast(1), true)
            }
            return bitmap
        }

    }

    private data class Track(val id: String?, val title: String?, val artist: String?)

    private data class MediaCardScene(
        val container: ViewGroup,
        val token: MediaSession.Token,
        val key: String,
        val name: String,
        val expiresAt: Long,
        var ended: Boolean = false,
        var peakDrawnWidth: Int = 0,
        var widestUnclipped: Int = 0,
        var coverSamples: Int = 0,
        var maxCoverError: Float = 0f,
    )

    private companion object {
        const val MEDIA_HANDOFF_MS = 300L
        const val METADATA_GRACE_MS = 750L
        const val CARD_SCENE_MS = 2_000L
        val INLINE_WORD_TIMESTAMP_REGEX = Regex("""<\d{1,4}:\d{2}(?:[.:]\d{1,3})?>""")
        val THREE_DIGIT_LINE_TS_REGEX = Regex("""\[(\d{1,4}):(\d{2})[.:](\d{3})]""")
        val INTERLUDE_HANDOFF_SCENES = setOf(
            "CAPSULE_TO_CARD", "CARD_TO_CAPSULE", "CARD_TO_CAPSULE_BY_INTERRUPT",
            "SINGLE_CAPSULE_TO_APP", "APP_TO_CAPSULE_WITH_NONE", "APP_TO_CAPSULE",
        )
    }
}
