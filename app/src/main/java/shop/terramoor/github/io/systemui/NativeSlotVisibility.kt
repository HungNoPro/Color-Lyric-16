package shop.terramoor.github.io.systemui

import android.view.View

/** Retains the native slot's measured space and restores only visibility/alpha changed by TerraMoor. */
internal class NativeSlotVisibility(private val view: View) {
    private var hiddenByModule = false
    private var savedAlpha = 1f

    fun apply(hidden: Boolean) {
        if (!hidden) {
            restore()
        } else {
            if (!hiddenByModule) {
                if (view.visibility == View.VISIBLE) {
                    savedAlpha = view.alpha
                    view.visibility = View.INVISIBLE
                    view.alpha = 0f
                    hiddenByModule = true
                } else if (view.visibility != View.INVISIBLE) {
                    hiddenByModule = false
                }
            } else {
                // Re-enforce on every frame so OEM Lottie / MediaSeedling updates cannot flicker it back.
                if (view.visibility == View.VISIBLE) view.visibility = View.INVISIBLE
                if (view.alpha != 0f) view.alpha = 0f
            }
        }
    }

    fun restore() {
        if (hiddenByModule) {
            if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
            if (view.alpha == 0f) view.alpha = savedAlpha
        }
        hiddenByModule = false
    }
}
