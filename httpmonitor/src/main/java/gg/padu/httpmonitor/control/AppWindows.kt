package gg.padu.httpmonitor.control

import android.view.View

/**
 * The app's own on-screen windows.
 *
 * An Activity is one window; a dialog, a spinner drop-down or a popup is another
 * one the framework layers over it. Anything that has to reason about what is
 * actually on screen — capturing it, or aiming a tap at it — needs the whole
 * stack, not just the Activity.
 *
 * Read from `WindowManagerGlobal` by reflection. Only this process's windows are
 * ever in it, so nothing belonging to another app can be reached from here.
 */
internal object AppWindows {

    /**
     * The attached, visible windows in the order the framework holds them —
     * addition order, which is bottom-to-top for app dialogs and popups. So the
     * last one that covers a point is the one a finger would have touched.
     */
    @Suppress("UNCHECKED_CAST", "PrivateApi")
    fun shown(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }

        val views = when (val value = field.get(instance)) {
            is List<*> -> value.filterIsInstance<View>()
            is Array<*> -> value.filterIsInstance<View>()
            else -> emptyList()
        }

        return views.filter { it.isShown && it.width > 0 && it.height > 0 }
    }
}
