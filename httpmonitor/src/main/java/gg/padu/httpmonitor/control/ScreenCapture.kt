package gg.padu.httpmonitor.control

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns the foreground Activity's own window into a compressed JPEG.
 *
 * It only ever reads the window of the app it is part of — there is no capture of
 * the device beyond it — so it needs no permission and shows the user nothing.
 * On Android 8+ the copy comes from [PixelCopy], which reads the composited
 * surface; older versions draw the view tree onto a canvas.
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
        val window = activity.window ?: return null
        val decor = window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            try {
                PixelCopy.request(window, bitmap, { result ->
                    ok.set(result == PixelCopy.SUCCESS)
                    latch.countDown()
                }, callbackHandler)
            } catch (_: IllegalArgumentException) {
                // The window is not backed by a surface yet — nothing to copy.
                bitmap.recycle()
                return null
            }
            val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return if (done && ok.get()) bitmap else null.also { bitmap.recycle() }
        }

        // API 24–25: draw the view tree, which must happen on the UI thread.
        val latch = CountDownLatch(1)
        val drawn = AtomicBoolean(false)
        activity.runOnUiThread {
            try {
                decor.draw(Canvas(bitmap))
                drawn.set(true)
            } catch (_: Throwable) {
                // A view that refuses to draw leaves drawn false; treated as a miss.
            } finally {
                latch.countDown()
            }
        }
        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return if (done && drawn.get()) bitmap else null.also { bitmap.recycle() }
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
