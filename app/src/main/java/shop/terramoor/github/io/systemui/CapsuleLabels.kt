package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LineMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/** Draws lyrics over the OEM capsule surface and leaves the OEM controls in their own views. */
internal class CapsuleLabels(
    val target: NativeContract.Target,
    initialMetrics: Pair<Int, Int>? = null,
    private val vocalModulator: ((rawPosMs: Long, lineStartMs: Long, lineEndMs: Long, nowMs: Long) -> Long)? = null,
) {
    private val capsule = target.capsule
    private val density = capsule.resources.displayMetrics.density
    private val strip = CapsuleLyricStrip(density)
    private val coverVisibility = NativeSlotVisibility(target.artwork)
    private val leftSectionVisibility = target.leftSection
        ?.takeIf { it !== capsule && it !== target.artwork && it !== target.title.frame && it !== target.translation.frame }
        ?.let(::NativeSlotVisibility)
    private val waveformVisibility = NativeSlotVisibility(target.waveform)
    private val rightSectionVisibility = target.rightSection
        ?.takeIf { it !== capsule && it !== target.waveform && it !== target.title.frame && it !== target.translation.frame }
        ?.let(::NativeSlotVisibility)
    private val titleStockVisibility = target.title.stock
        .takeIf { it !== capsule && it !== target.title.frame && it !== target.artwork && it !== target.waveform }
        ?.let(::NativeSlotVisibility)
    private val translationStockVisibility = target.translation.stock
        .takeIf { it !== capsule && it !== target.translation.frame && it !== target.title.stock && it !== target.artwork && it !== target.waveform }
        ?.let(::NativeSlotVisibility)
    private val filler = View(capsule.context).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var artwork: Bitmap? = null
    private var ink = LyricInk.WHITE
    private var dimAlpha = 0
    private var displayLine: LyricFrame? = null
    private var displayTrackIdentity: Any? = null
    private var clock: PlaybackClock? = null
    private var trackInfoShown = false
    private val trackInfoMarquee = TrackInfoDisplayMarquee()
    private val redrawContent = Runnable { if (capsule.isShown) capsule.invalidate() }
    private var displaySettings: CapsuleSettings? = null
    private var sizedParams: ViewGroup.LayoutParams? = null
    private var nativeWidth = initialMetrics?.first ?: target.capsule.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
    private var nativeMinimumWidth = initialMetrics?.second ?: capsule.minimumWidth
    private var appliedWidth: Int? = null
    private val viewport = CapsuleVisualViewport(target)
    private var viewportStartedAt: Long? = null
    private var viewportPosition: Long? = null
    private var pauseCollapseStartedAt: Long? = null
    private var trackBounceStartedAt: Long? = null
    private val artworkBackground = CapsuleArtworkBackground()
    private val area = RectF()
    private val artworkArea = RectF()
    private val clip = Path()
    private val location = IntArray(2)

    val hasCustomContent: Boolean get() = displayLine != null || artwork != null
    val hasInterludeViewport: Boolean get() = displayLine?.countdown?.isIntro == false
    val viewportWidth: Int get() = (capsule.width - viewport.inset * 2).coerceAtLeast(0)
    val coverAnchorX: Float? get() = viewport.coverAnchorX
    val shouldReserveCompactStatusBarSpace: Boolean get() {
        val countdown = displayLine?.countdown?.takeUnless { it.isIntro } ?: return false
        val position = viewportPosition ?: return false
        return viewport.inset > 0 && viewportWidth <= INTERLUDE_STATUS_BAR_WIDTH_PX &&
            countdown.endMs - position > INTERLUDE_REOPEN_MS
    }
    var nativeWhilePaused = false
        private set
    val isCollapsingForPause: Boolean get() = pauseCollapseStartedAt != null
    val isBouncingForTrack: Boolean get() = trackBounceStartedAt != null

    fun render(
        frame: LyricFrame?, settings: CapsuleSettings, artwork: Bitmap?,
        clock: PlaybackClock? = null, ink: LyricInk = LyricInk.WHITE, trackIdentity: Any? = null,
    ) {
        if (pauseCollapseStartedAt != null) {
            pauseCollapseStartedAt = null
            suspendViewport()
        }
        nativeWhilePaused = false
        val line = if (frame != null && !frame.isTrackInfo && frame.countdown == null &&
            trackIdentity != null && trackIdentity === displayTrackIdentity &&
            clock?.moving == true && settings.motion != LineMotion.NONE
        ) {
            val position = clock.position(SystemClock.elapsedRealtime())
            val outgoing = displayLine
            when {
                outgoing?.isTrackInfo == true && trackInfoShown -> frame.copy(
                    priorRows = outgoing.rows, priorCountdown = null,
                    priorTrackProgress = trackInfoMarquee.progress, motionStartMs = position,
                )
                outgoing?.priorTrackProgress != null && outgoing.displayStartMs == frame.displayStartMs &&
                    position in outgoing.motionStartMs until (outgoing.motionStartMs + LYRIC_MOTION_DURATION_MS) -> frame.copy(
                        priorRows = outgoing.priorRows, priorCountdown = null,
                        priorTrackProgress = outgoing.priorTrackProgress, motionStartMs = outgoing.motionStartMs,
                    )
                else -> frame
            }
        } else frame
        val showingTrackInfo = line?.isTrackInfo == true && capsule.isShown
        val trackInfoVisibilityChanged = trackInfoShown != showingTrackInfo
        trackInfoShown = showingTrackInfo
        if (displayLine != line || trackInfoVisibilityChanged) {
            capsule.removeCallbacks(redrawContent)
            trackInfoMarquee.reset()
        }
        val changed = displayLine != line || displaySettings != settings || this.artwork !== artwork ||
            this.clock != clock || this.ink != ink || trackInfoVisibilityChanged
        val isTrackSwitch = displayTrackIdentity != null && trackIdentity != null && displayTrackIdentity !== trackIdentity
        if (displayLine?.countdown != line?.countdown || displayTrackIdentity !== trackIdentity ||
                displaySettings?.showCover != settings.showCover || displaySettings?.showWaveform != settings.showWaveform ||
                displaySettings?.get(CapsuleTuning.ISLAND_WIDTH) != settings[CapsuleTuning.ISLAND_WIDTH]) {
            suspendViewport()
        }
        if (isTrackSwitch && capsule.isShown && capsule.width > 0) {
            trackBounceStartedAt = SystemClock.elapsedRealtime()
            capsule.postInvalidateOnAnimation()
        }
        viewportPosition = null
        this.clock = clock
        displayLine = line
        displayTrackIdentity = trackIdentity
        displaySettings = settings
        enforceNativeVisibility()
        this.artwork = artwork
        this.ink = ink
        dimAlpha = alpha(settings[CapsuleTuning.ARTWORK_DIM])
        if (changed) {
            strip.update(line, settings, ink)
            capsule.invalidate()
        }
        maintainNativeSizing()
    }

    fun maintainNativeSizing() {
        val settings = displaySettings ?: return
        // Keep the verified slot measured across gaps and card transitions. A single invisible
        // child lets the native container measure its own width, including reserved icon space.
        val normalWidth = capsuleWidthPixels(settings[CapsuleTuning.ISLAND_WIDTH], density)
        updateCapsuleWidth(normalWidth)
        setFillerWidth(capsuleWidthPixels(CapsuleTuning.ISLAND_WIDTH.range.last / 2, density))
        if (capsule.width > 0 && viewport.inset == 0) alignTrailingSection(capsule.width)
    }

    fun prepareViewport(now: Long, canCompact: Boolean, resumeInterlude: Boolean = false,
                        animatedWidth: Int? = null): Boolean {
        val settings = displaySettings ?: return false
        val line = displayLine ?: return false
        val countdown = line.countdown?.takeUnless { it.isIntro } ?: return false
        val width = capsule.width
        val height = capsule.height.toFloat()
        if (!canCompact || width <= 0 || height <= 0f ||
            (settings.showCover && target.artwork.width <= 0) || (settings.showWaveform && target.waveform.width <= 0)) {
            suspendViewport()
            return false
        }
        val position = clock?.position(now) ?: line.startMs
        val lens = resolveCamera(includeWindowFallback = true)
        val leading = capsuleLyricsLeft(layoutLeft(target.artwork, 0f) + target.artwork.width, density, settings)
        val trailing = if (settings.showWaveform) {
            maxOf(target.waveform.width.toFloat(), width - layoutLeft(target.waveform, width.toFloat()))
        } else 3f * density
        val requested = interludeCapsuleWidth(countdown, position, width, height, density,
            leading, trailing, lens?.let { it.x - width / 2f } ?: 0f, lens?.radiusX ?: 0f,
            line.priorRows != null && settings.motion != LineMotion.NONE)
        val startedAt = viewportStartedAt ?: if (resumeInterlude) now - VIEWPORT_HANDOFF_MS else now
        viewportStartedAt = startedAt
        val (interludeInset, settling) = interludeViewportInset(width, requested, now - startedAt)
        val nativeInset = animatedWidth?.let { nativeCapsuleClipLeft(width, it) } ?: 0
        val inset = maxOf(interludeInset, nativeInset).coerceAtMost((width - 1) / 2)
        if (!viewport.apply(inset, settings.showCover, settings.showWaveform, followEdge = resumeInterlude)) {
            suspendViewport()
            return false
        }
        // The width envelope and the countdown paint use the same playback position.
        viewportPosition = position
        if (settling) capsule.postInvalidateOnAnimation()
        return true
    }

    fun beginRestoreForPause(): Boolean {
        if (nativeWhilePaused) return false
        trackBounceStartedAt = null
        if (displaySettings == null || !capsule.isShown || capsule.width <= 0 || (displayLine == null && artwork == null)) {
            restoreForPause()
            return false
        }
        if (pauseCollapseStartedAt == null) {
            pauseCollapseStartedAt = SystemClock.elapsedRealtime()
            capsule.postInvalidateOnAnimation()
        }
        return true
    }

    private fun compactMaxInset(width: Int, showCover: Boolean, showWaveform: Boolean): Float {
        val lens = resolveCamera(includeWindowFallback = true)
        val cameraWidth = (lens?.radiusX?.times(2f) ?: (32f * density))
        val coverSlot = if (showCover) maxOf(target.artwork.width.toFloat(), 20f * density) + 14f * density else 12f * density
        val waveSlot = if (showWaveform) maxOf(target.waveform.width.toFloat(), 20f * density) + 14f * density else 12f * density
        val compactTarget = (coverSlot + cameraWidth + waveSlot).coerceIn(88f * density, width.toFloat())
        return ((width - compactTarget) / 2f).coerceAtLeast(0f)
    }

    private fun trackBounceShrinkFraction(now: Long): Float? {
        val startedAt = trackBounceStartedAt ?: return null
        val rawT = ((now - startedAt).toFloat() / TRACK_BOUNCE_DURATION_MS).coerceIn(0f, 1f)
        if (rawT >= 1f) return null
        return if (rawT <= TRACK_BOUNCE_SHRINK_RATIO) {
            val t = rawT / TRACK_BOUNCE_SHRINK_RATIO
            // Smooth cubic ease-in-out shrink toward compact pill
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        } else {
            val t = (rawT - TRACK_BOUNCE_SHRINK_RATIO) / (1f - TRACK_BOUNCE_SHRINK_RATIO)
            // Smooth cubic ease-out expand back to full pill
            val open = 1f - (1f - t) * (1f - t) * (1f - t)
            1f - open
        }
    }

    fun prepareTrackTransition(now: Long): Boolean {
        val shrink = trackBounceShrinkFraction(now)
        val width = capsule.width
        if (shrink == null || !capsule.isShown || width <= 0) {
            trackBounceStartedAt = null
            suspendViewport()
            return false
        }
        val settings = displaySettings
        val showCover = settings?.showCover != false
        val showWaveform = settings?.showWaveform != false
        val maxInset = compactMaxInset(width, showCover, showWaveform)
        val inset = (maxInset * shrink).roundToInt().coerceAtMost((width - 1) / 2)
        viewport.apply(inset, moveLeading = showCover, moveTrailing = showWaveform, followEdge = false)
        capsule.postInvalidateOnAnimation()
        return true
    }

    fun preparePauseCollapse(now: Long): Boolean {
        val startedAt = pauseCollapseStartedAt ?: return false
        val width = capsule.width
        if (!capsule.isShown || width <= 0) {
            pauseCollapseStartedAt = null
            restoreForPause()
            return false
        }
        val rawT = ((now - startedAt).toFloat() / PAUSE_COLLAPSE_DURATION_MS).coerceIn(0f, 1f)
        if (rawT >= 1f) {
            pauseCollapseStartedAt = null
            restoreForPause()
            return false
        }
        val ease = 1f - (1f - rawT) * (1f - rawT) * (1f - rawT)
        val settings = displaySettings
        val showCover = settings?.showCover != false
        val showWaveform = settings?.showWaveform != false
        val maxInset = compactMaxInset(width, showCover, showWaveform)
        val inset = (maxInset * ease).roundToInt().coerceAtMost((width - 1) / 2)
        viewport.apply(inset, moveLeading = showCover, moveTrailing = showWaveform, followEdge = false)
        capsule.postInvalidateOnAnimation()
        return true
    }

    fun suspendViewport() {
        viewport.restore()
        viewportStartedAt = null
        viewportPosition = null
    }

    fun paintDiagnostics() = strip.paintDiagnostics()

    fun draw(canvas: Canvas, animatedWidth: Int?): String {
        if (!capsule.isShown) return "CAPSULE_HIDDEN"
        if (displayLine == null && artwork == null) return "NO_LYRIC_OR_ARTWORK"
        // Re-enforce cover/waveform hiding right before child views draw to eliminate OEM flicker.
        enforceNativeVisibility()
        val width = capsule.width
        val height = capsule.height.toFloat()
        if (width <= 0 || height <= 0) return "EMPTY_DRAWING_AREA"
        // If OEM animation left the native background/foreground bounds stuck at 300px/59px, sync them immediately.
        if (animatedWidth == null) {
            capsule.background?.let { bg ->
                if (bg.bounds.left != 0 || bg.bounds.right != width || bg.bounds.bottom != capsule.height) {
                    bg.setBounds(0, 0, width, capsule.height)
                }
            }
            capsule.foreground?.let { fg ->
                if (fg.bounds.left != 0 || fg.bounds.right != width || fg.bounds.bottom != capsule.height) {
                    fg.setBounds(0, 0, width, capsule.height)
                }
            }
        }
        // During a media card handoff the outline still supplies the interlude viewport.
        // The native animated clip can narrow it further without moving the lyric slots.
        val inset = viewport.inset
        val drawingWidth = animatedWidth ?: (width - 2 * inset)
        // Clip to the OEM drawable bounds without using the clip to resize the artwork or lyrics.
        val shapeLeft = if (animatedWidth != null) nativeCapsuleClipLeft(capsule.measuredWidth, drawingWidth) else inset
        area.set(shapeLeft.toFloat(), 0f, (shapeLeft + drawingWidth).toFloat(), height)
        artworkArea.set(0f, 0f, width.toFloat(), height)
        clip.rewind()
        val radius = area.height() / 2f
        clip.addRoundRect(area, radius, radius, Path.Direction.CW)
        val now = SystemClock.elapsedRealtime()
        val collapseT = pauseCollapseStartedAt?.let {
            ((now - it).toFloat() / PAUSE_COLLAPSE_DURATION_MS).coerceIn(0f, 1f)
        }
        val bounceShrink = if (collapseT == null) trackBounceShrinkFraction(now) else null
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            artwork?.takeUnless { it.isRecycled }?.let { bmp ->
                val artAlpha = if (collapseT == null) 1f else ((1f - collapseT) / 0.55f).coerceIn(0f, 1f)
                if (artAlpha >= 0.99f) {
                    artworkBackground.draw(canvas, bmp, artworkArea, area, dimAlpha)
                } else if (artAlpha > 0.01f) {
                    val layer = canvas.saveLayerAlpha(area, (artAlpha * 255).roundToInt())
                    try {
                        artworkBackground.draw(canvas, bmp, artworkArea, area, dimAlpha)
                    } finally {
                        canvas.restoreToCount(layer)
                    }
                }
            }
            val lyricOpacity = when {
                collapseT != null -> (1f - collapseT * 2.2f).coerceIn(0f, 1f)
                bounceShrink != null -> (1f - bounceShrink * 1.35f).coerceIn(0f, 1f)
                else -> 1f
            }
            if ((collapseT != null || bounceShrink != null) && lyricOpacity <= 0.01f) {
                capsule.postInvalidateOnAnimation()
                return if (collapseT != null) "PAUSE_COLLAPSE" else "TRACK_BOUNCE"
            }
            val line = displayLine ?: return "ARTWORK_ONLY"
            val settings = displaySettings ?: return "ARTWORK_ONLY"
            val lens = resolveCamera(line.countdown != null || line.priorCountdown != null || settings.cameraWordAvoidance)
            val iconStart = layoutLeft(target.artwork, 0f)
            val rightShift = alignTrailingSection(width)
            val waveformLeft = (layoutLeft(target.waveform, width.toFloat()) + rightShift).coerceIn(0f, width.toFloat())
            val left = capsuleLyricsLeft(iconStart + target.artwork.width, density, settings) + inset
            val right = capsuleLyricsRight(width.toFloat(), waveformLeft, density, settings) - inset
            val timing = clock
            val rawPosition = if (line.isTrackInfo) now else viewportPosition ?: timing?.position(now) ?: line.startMs
            val position = if (!line.isTrackInfo && viewportPosition == null && timing?.moving == true && vocalModulator != null) {
                val lineEnd = line.endMs ?: (line.startMs + 6_000L)
                vocalModulator.invoke(rawPosition, line.startMs, lineEnd, now)
            } else rawPosition
            val settled = animatedWidth == null && bounceShrink == null && abs(width - capsule.measuredWidth) <= 1
            val redrawDelay = strip.draw(
                canvas, left, area.top, right, area.bottom,
                lens, position, opacity = lyricOpacity, artworkArea = artworkArea,
                trackInfoMarquee = trackInfoMarquee, trackInfoSettled = settled,
            )
            capsule.removeCallbacks(redrawContent)
            if (collapseT != null || bounceShrink != null) {
                capsule.postInvalidateOnAnimation()
            } else if (line.isTrackInfo) {
                if (capsule.windowVisibility == View.VISIBLE && redrawDelay != null) {
                    if (redrawDelay == 0L) capsule.postInvalidateOnAnimation()
                    else capsule.postDelayed(redrawContent, redrawDelay)
                }
            } else if (redrawDelay != null && timing?.moving == true && timing.speed > 0f) {
                if (redrawDelay == 0L) capsule.postInvalidateOnAnimation()
                else timing.delayTo(position, position + redrawDelay)?.let { capsule.postDelayed(redrawContent, it) }
            }
            return "LYRICS_DRAWN"
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun resolveCamera(includeWindowFallback: Boolean): CapsuleCamera? {
        // Display cutout and getLocationOnScreen share display coordinates. Insets
        // delivered to the native capsule may already have consumed the cutout.
        val displayCamera = capsule.topDisplayCutout()
        val windowCamera = if (displayCamera == null && includeWindowFallback) {
            capsule.rootWindowInsets?.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        } else null
        val camera = displayCamera ?: windowCamera ?: return null
        if (windowCamera != null) capsule.getLocationInWindow(location) else capsule.getLocationOnScreen(location)
        val scaleX = capsule.scaleX.takeIf { it > 0f } ?: 1f
        val oemBlankWidth = try {
            val parent = capsule.parent
            val m = parent?.javaClass?.getMethod("getBlankWidth")
            (m?.invoke(parent) as? Int)?.takeIf { it > 0 && it < camera.width() }
        } catch (_: Throwable) {
            null
        }
        val holeWidth = (oemBlankWidth ?: camera.width()).toFloat()
        return CapsuleCamera((camera.exactCenterX() - location[0]) / scaleX, holeWidth / (2f * scaleX))
    }

    private var appliedTrailingShift = false

    private fun alignTrailingSection(capsuleWidth: Int): Float {
        val right = target.rightSection ?: return 0f
        if (right.width <= 0 || capsuleWidth <= 0) return 0f
        val rightEdge = layoutLeft(right, capsuleWidth.toFloat()) + right.width
        val gap = (capsuleWidth - rightEdge).coerceAtLeast(0f)
        if (gap > 2f) {
            var ancestor = right.parent as? ViewGroup
            while (ancestor != null) {
                if (ancestor.clipChildren) ancestor.clipChildren = false
                if (ancestor.clipToPadding) ancestor.clipToPadding = false
                if (ancestor === capsule) break
                ancestor = ancestor.parent as? ViewGroup
            }
            if (abs(right.translationX - gap) > 0.5f) right.translationX = gap
            appliedTrailingShift = true
            return gap
        } else if (appliedTrailingShift) {
            right.translationX = 0f
            appliedTrailingShift = false
        }
        return 0f
    }

    private fun restoreTrailingSection() {
        if (!appliedTrailingShift) return
        target.rightSection?.translationX = 0f
        appliedTrailingShift = false
    }

    private fun layoutLeft(view: View, fallback: Float): Float {
        if (view.width <= 0) return fallback
        // The OEM translates the controls independently while opening or replacing the content.
        // Those translations must not move the lyric viewport or change its scroll distance.
        var left = 0
        var child = view
        while (child !== capsule) {
            val parent = child.parent as? View ?: return fallback
            left += child.left - parent.scrollX
            child = parent
        }
        return left.toFloat()
    }

    private fun updateCapsuleWidth(requestedWidth: Int?) {
        val params = capsule.layoutParams ?: return
        if (params !== sizedParams) {
            sizedParams = params
            if (requestedWidth != null && appliedWidth == null && params.width <= 0) {
                nativeWidth = params.width
            }
            appliedWidth = null
        }
        val width = requestedWidth ?: nativeWidth
        val minimum = if (requestedWidth == null) nativeMinimumWidth else width
        var changed = false
        if (capsule.minimumWidth != minimum) {
            capsule.minimumWidth = minimum
            changed = true
        }
        if (params.width != width) {
            params.width = width
            capsule.layoutParams = params
            changed = true
        }
        val parent = capsule.parent as? ViewGroup
        if (requestedWidth != null) {
            if (capsule.clipChildren) capsule.clipChildren = false
            if (capsule.clipToPadding) capsule.clipToPadding = false
            if (parent != null) {
                if (parent.clipChildren) parent.clipChildren = false
                if (parent.clipToPadding) parent.clipToPadding = false
            }
            // ColorOS 16 unlock/card-exit animations can leave measuredWidth stuck at 300px
            // without changing LayoutParams.width; only relayout when measuredWidth itself disagrees.
            if (!changed && capsule.isLaidOut && capsule.measuredWidth != width && !capsule.isLayoutRequested) {
                changed = true
            }
        }
        if (changed) {
            capsule.requestLayout()
            if (requestedWidth != null && parent != null && parent.width < width && !parent.isLayoutRequested) {
                parent.requestLayout()
            }
        }
        appliedWidth = if (requestedWidth == null) null else width.takeUnless { it == nativeWidth }
    }

    private fun setFillerWidth(width: Int) {
        val frame = target.title.frame
        val lp = if (frame is FrameLayout) FrameLayout.LayoutParams(width, 1, Gravity.START)
        else ViewGroup.LayoutParams(width, 1)
        if (filler.parent == null) frame.addView(filler, lp)
        else if (filler.layoutParams.width != width) {
            filler.layoutParams = lp
        }
    }

    private fun clearFiller() {
        val frame = target.title.frame
        if (filler.parent === frame) frame.removeView(filler)
    }

    fun enforceNativeVisibility() {
        val settings = displaySettings ?: return
        coverVisibility.apply(!settings.showCover)
        leftSectionVisibility?.apply(!settings.showCover)
        waveformVisibility.apply(!settings.showWaveform)
        rightSectionVisibility?.apply(!settings.showWaveform)
        val hideStock = displayLine != null
        titleStockVisibility?.apply(hideStock)
        translationStockVisibility?.apply(hideStock)
    }

    fun restoreForPause() {
        pauseCollapseStartedAt = null
        nativeWhilePaused = true
        close()
    }

    fun close() {
        pauseCollapseStartedAt = null
        trackBounceStartedAt = null
        suspendViewport()
        restoreTrailingSection()
        updateCapsuleWidth(null)
        if (displaySettings == null) return
        displaySettings = null
        capsule.removeCallbacks(redrawContent)
        coverVisibility.restore()
        leftSectionVisibility?.restore()
        waveformVisibility.restore()
        rightSectionVisibility?.restore()
        titleStockVisibility?.restore()
        translationStockVisibility?.restore()
        clearFiller()
        displayLine = null
        displayTrackIdentity = null
        artwork = null
        clock = null
        trackInfoShown = false
        trackInfoMarquee.reset()
        capsule.invalidate()
    }

    private fun alpha(percent: Int): Int = (percent * 255 + 50) / 100

    private companion object {
        const val PAUSE_COLLAPSE_DURATION_MS = 280L
        const val TRACK_BOUNCE_DURATION_MS = 420L
        const val TRACK_BOUNCE_SHRINK_RATIO = 0.38f
    }
}
