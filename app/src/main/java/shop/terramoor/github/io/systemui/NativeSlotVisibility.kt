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
                savedAlpha = if (view.alpha > 0f) view.alpha else 1f
                hiddenByModule = true
            }
            // Always re-enforce on every frame so OEM Lottie / MediaSeedling / border wrappers cannot flicker back.
            if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
            if (view.alpha != 0f) view.alpha = 0f
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
