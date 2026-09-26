package gg.padu.httpmonitor.control

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a tap back onto the app's own UI.
 *
 * The dashboard points at a spot on a screenshot; this turns that spot into the
 * down/up pair a finger would have produced and hands it to the window actually
 * under it — the Activity, or whatever dialog or popup sits over it. The events
 * go into this process's own view tree, which is the app's to touch, so nothing
 * is prompted and **no permission is involved**. It is also the limit of the
 * feature: the system UI, the keyboard and other apps are out of reach by
 * construction, because they are not this app's windows.
 *
 * Coordinates arrive as fractions of the screenshot rather than pixels, so the
 * dashboard never has to know what the device downscaled the image to.
 */
internal class RemoteInput {

    /** Where the tap goes, resolved on the UI thread before any event is sent. */
    private class Aim(val target: View, val screenX: Float, val screenY: Float, val left: Int, val top: Int)

    /**
     * Sends one tap and waits for it to be delivered, returning whether it was.
     * The wait is for the dispatch, not for whatever the app does in response.
     */
    fun tap(activity: Activity, tap: ControlProtocol.Tap, timeoutMs: Long): Boolean {
        val decor = activity.window?.decorView ?: return false

        val latch = CountDownLatch(1)
        val delivered = AtomicBoolean(false)

        activity.runOnUiThread {
            val aim = runCatching { aim(decor, tap) }.getOrNull()
            if (aim == null) {
                latch.countDown()
                return@runOnUiThread
            }

            val downTime = SystemClock.uptimeMillis()
            if (!send(aim, MotionEvent.ACTION_DOWN, downTime)) {
                latch.countDown()
                return@runOnUiThread
            }

            // The gap between down and up is what makes a tap a tap: zero would
            // land, but a long press needs real time to pass, and the app's own
            // press feedback needs a frame or two to be worth anything.
            aim.target.postDelayed({
                // A down with no up leaves the view pressed for good; if the up
                // cannot go, cancel the gesture rather than abandon it.
                if (send(aim, MotionEvent.ACTION_UP, downTime)) delivered.set(true)
                else send(aim, MotionEvent.ACTION_CANCEL, downTime)

                latch.countDown()
            }, tap.holdMs)
        }

        val done = latch.await(timeoutMs + tap.holdMs, TimeUnit.MILLISECONDS)
        return done && delivered.get()
    }

    /**
     * Resolves the fraction against the Activity's decor — the same frame the
     * screenshot was taken in — then finds the window under the resulting point.
     */
    private fun aim(decor: View, tap: ControlProtocol.Tap): Aim? {
        if (decor.width <= 0 || decor.height <= 0) return null

        val base = IntArray(2).also { decor.getLocationOnScreen(it) }
        val screenX = base[0] + tap.x * decor.width
        val screenY = base[1] + tap.y * decor.height

        val target = topmostAt(decor, screenX, screenY)
        val origin = IntArray(2).also { target.getLocationOnScreen(it) }

        return Aim(target, screenX, screenY, origin[0], origin[1])
    }

    /**
     * The app's topmost window covering the point, so a tap meant for a dialog
     * does not go to the screen behind it. A point no window covers — the dimmed
     * margin around a dialog — is given to the Activity's window, which is what
     * decides whether an outside touch dismisses anything.
     */
    private fun topmostAt(decor: View, screenX: Float, screenY: Float): View {
        val roots = runCatching { AppWindows.shown() }.getOrNull().orEmpty()
        val spot = IntArray(2)

        return roots.lastOrNull { root ->
            root.getLocationOnScreen(spot)
            screenX >= spot[0] && screenX < spot[0] + root.width &&
                screenY >= spot[1] && screenY < spot[1] + root.height
        } ?: decor
    }

    /** One event into one window. A view that throws on it fails the whole tap. */
    private fun send(aim: Aim, action: Int, downTime: Long): Boolean = runCatching {
        val event = event(action, downTime, aim.screenX, aim.screenY)
        try {
            // Built in screen coordinates and then shifted into the window's, so
            // getRawX/getRawY read as they would for a real finger.
            event.offsetLocation(-aim.left.toFloat(), -aim.top.toFloat())
            aim.target.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }.isSuccess

    /**
     * A single-pointer touch event. Built through the long [MotionEvent.obtain]
     * so it carries a touchscreen source and a finger tool type: views and
     * Compose both branch on those, and an event without them is not treated as
     * a touch everywhere.
     */
    private fun event(action: Int, downTime: Long, x: Float, y: Float): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })

        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            1,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }
}
