package gg.padu.httpmonitor.control

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns what the app is showing into a compressed JPEG.
 *
 * It only ever reads windows belonging to the app it is part of — each app
 * process sees only its own — so it needs no permission and shows the user
 * nothing.
 *
 * A dialog, a spinner drop-down or a popup is a *separate window* the framework
 * layers over the Activity, so copying the Activity's window alone would miss it.
 * When more than one of the app's windows is on screen this composites them all
 * in z-order; when only the Activity is up it takes the faster, pixel-accurate
 * [PixelCopy] of that one window.
 */
internal class ScreenCapture(private val callbackHandler: Handler) {

    data class Shot(val bytes: ByteArray, val width: Int, val height: Int)

    fun capture(activity: Activity, maxDimension: Int, jpegQuality: Int, timeoutMs: Long): Shot? {
        val bitmap = grab(activity, timeoutMs) ?: return null
        return try {
            encode(bitmap, maxDimension, jpegQuality)
        } finally {
            bitmap.recycle()
        }
    }

    private fun grab(activity: Activity, timeoutMs: Long): Bitmap? {
        val decor = activity.window?.decorView ?: return null
        if (decor.width <= 0 || decor.height <= 0) return null

        // More than one window up (a dialog, a popup) means the Activity's window
        // does not hold everything on screen — composite them. A single window is
        // the common case and takes the higher-fidelity PixelCopy.
        val roots = runCatching { shownRoots() }.getOrNull().orEmpty()
        if (roots.size > 1) {
            composite(activity, decor, roots, timeoutMs)?.let { return it }
            // Compositing failed; fall through to the single-window copy.
        }

        return copyWindow(activity, timeoutMs)
    }

    /** The one-window path: an accurate copy of the Activity's own surface. */
    private fun copyWindow(activity: Activity, timeoutMs: Long): Bitmap? {
        val window = activity.window ?: return null
        val decor = window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            try {
                PixelCopy.request(window, bitmap, { result ->
                    ok.set(result == PixelCopy.SUCCESS)
                    latch.countDown()
                }, callbackHandler)
            } catch (_: IllegalArgumentException) {
                bitmap.recycle()
                return null
            }
            val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return if (done && ok.get()) bitmap else null.also { bitmap.recycle() }
        }

        return drawViews(activity, listOf(decor), decor, timeoutMs)
    }

    /**
     * Draws every on-screen window of the app into one bitmap, bottom to top, each
     * offset to where it actually sits — so the Activity, the dialog over it and
     * any popup over that all land in the image. A dimmed window paints its scrim
     * first, so the darkening behind a dialog is reproduced.
     *
     * This is a software draw: ordinary views and dialogs come out right, but a
     * pure-GPU surface (a SurfaceView, video or GL view) in a lower window may be
     * blank — a fair trade for catching the dialog that prompted the capture.
     */
    private fun composite(activity: Activity, decor: View, roots: List<View>, timeoutMs: Long): Bitmap? =
        drawViews(activity, roots, decor, timeoutMs)

    private fun drawViews(activity: Activity, views: List<View>, decor: View, timeoutMs: Long): Bitmap? {
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)

        // draw() must run on the UI thread; the views are live.
        activity.runOnUiThread {
            try {
                val base = IntArray(2).also { decor.getLocationOnScreen(it) }
                val here = IntArray(2)
                for (view in views) {
                    paintDim(canvas, view, width, height)
                    view.getLocationOnScreen(here)
                    canvas.save()
                    canvas.translate((here[0] - base[0]).toFloat(), (here[1] - base[1]).toFloat())
                    view.draw(canvas)
                    canvas.restore()
                }
                ok.set(true)
            } catch (_: Throwable) {
                // A view that refuses to draw leaves ok false; treated as a miss.
            } finally {
                latch.countDown()
            }
        }

        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return if (done && ok.get()) bitmap else null.also { bitmap.recycle() }
    }

    /** The scrim a FLAG_DIM_BEHIND window casts over everything below it. */
    private fun paintDim(canvas: Canvas, view: View, width: Int, height: Int) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val dims = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        if (!dims || params.dimAmount <= 0f) return

        val alpha = (255 * params.dimAmount).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            color = Color.argb(alpha, 0, 0, 0)
        })
    }

    /**
     * The app's attached, visible windows in the order the framework holds them —
     * addition order, which is bottom-to-top for app dialogs and popups. Read from
     * WindowManagerGlobal by reflection; only this process's windows are ever in
     * it, so nothing outside the app can be reached.
     */
    @Suppress("UNCHECKED_CAST", "PrivateApi")
    private fun shownRoots(): List<View> {
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

    private fun encode(bitmap: Bitmap, maxDimension: Int, jpegQuality: Int): Shot {
        val scaled = scaleDown(bitmap, maxDimension)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        return Shot(out.toByteArray(), scaled.width, scaled.height).also {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Keeps the longest side within [maxDimension] — a clear image, not a crisp one. */
    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap

        val ratio = maxDimension.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
