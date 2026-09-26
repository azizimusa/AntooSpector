package gg.padu.httpmonitor.control

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays taps, key presses and typed text back onto the app's own UI.
 *
 * The dashboard points at a spot on a screenshot, or names a key, or hands over a
 * line of text; this turns each into the events a finger or a keyboard would have
 * produced and hands them to the window they belong to — the Activity, or
 * whatever dialog or popup sits over it. The events go into this process's own
 * view tree, which is the app's to touch, so nothing is prompted and **no
 * permission is involved**. It is also the limit of the feature: the system UI,
 * the notification shade, Home and Recents, and other apps are out of reach by
 * construction, because they are not this app's windows.
 *
 * Typed text goes in as key events, not through the on-screen keyboard, so it
 * lands in whatever has focus whether or not an IME is up.
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
     * Presses one key and releases it. Back is the reason this exists: it is the
     * app's own navigation, and the only way to walk out of a screen from the
     * dashboard.
     */
    fun key(activity: Activity, keyCode: Int, timeoutMs: Long): Boolean =
        onUiThread(activity, timeoutMs) { target ->
            press(target, keyCode)
        }

    /**
     * Types text into whatever has focus.
     *
     * [KeyCharacterMap] turns the string into the key strokes a keyboard would
     * have sent, which is what an EditText, a Compose field and a WebView all
     * understand. A character no virtual key produces — an emoji, most non-Latin
     * script — has no such stroke, and goes in as one text event instead, which
     * editable views commit whole.
     */
    fun text(activity: Activity, text: String, timeoutMs: Long): Boolean =
        onUiThread(activity, timeoutMs) { target ->
            val strokes = runCatching {
                KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            }.getOrNull()

            if (strokes == null || strokes.isEmpty()) commit(target, text) else type(target, strokes)
        }

    /** The down/up pair of one key press. */
    private fun press(target: View, keyCode: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = key(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 0)

        if (!send(target, down)) return false

        // FLAG_TRACKING is set by the framework on an up whose down was tracked,
        // and Activity and Dialog both refuse to act on Back without it. Nothing
        // sets it for us here, because dispatching straight into the view tree
        // skips the ViewRootImpl that would have.
        val up = key(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, 0, KeyEvent.FLAG_TRACKING)

        return send(target, up)
    }

    /** Replays a keyboard's strokes with times of our own, so they read as now. */
    private fun type(target: View, strokes: Array<KeyEvent>): Boolean {
        // An up must carry the down time of its own key, or a view pairing them
        // sees a stroke that was never pressed.
        val downTimes = mutableMapOf<Int, Long>()

        return strokes.all { stroke ->
            val now = SystemClock.uptimeMillis()
            val downTime = if (stroke.action == KeyEvent.ACTION_DOWN) {
                now.also { downTimes[stroke.keyCode] = it }
            } else {
                downTimes[stroke.keyCode] ?: now
            }

            send(target, key(downTime, now, stroke.action, stroke.keyCode, stroke.metaState, stroke.scanCode, 0))
        }
    }

    /** Text that no key stroke spells, handed over whole for a view to commit. */
    private fun commit(target: View, text: String): Boolean {
        val now = SystemClock.uptimeMillis()
        // The one-time constructor: a text event has no press to hold, only a moment.
        val event = KeyEvent(now, text, KeyCharacterMap.VIRTUAL_KEYBOARD, KeyEvent.FLAG_SOFT_KEYBOARD)

        return send(target, event)
    }

    private fun key(
        downTime: Long,
        eventTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int,
        scanCode: Int,
        extraFlags: Int
    ): KeyEvent = KeyEvent(
        downTime,
        eventTime,
        action,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        scanCode,
        // Soft keyboard and keep-touch-mode together are what a real IME sends:
        // without them a view can be dragged out of touch mode and move focus
        // somewhere the operator never asked for.
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE or extraFlags,
        InputDevice.SOURCE_KEYBOARD
    )

    private fun send(target: View, event: KeyEvent): Boolean =
        runCatching { target.dispatchKeyEvent(event) }.isSuccess

    /**
     * Runs [action] against the focused window on the UI thread and waits for it.
     * Keys go where typing goes — the window that holds focus — which is not
     * always the topmost one a tap would land in.
     */
    private fun onUiThread(activity: Activity, timeoutMs: Long, action: (View) -> Boolean): Boolean {
        val decor = activity.window?.decorView ?: return false

        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)

        activity.runOnUiThread {
            try {
                done.set(action(focused(decor)))
            } catch (_: Throwable) {
                // Left false: the caller reports it as undelivered.
            } finally {
                latch.countDown()
            }
        }

        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && done.get()
    }

    /** The app's focused window, or its topmost one if the framework names none. */
    private fun focused(decor: View): View {
        val roots = runCatching { AppWindows.shown() }.getOrNull().orEmpty()

        return roots.lastOrNull { it.hasWindowFocus() } ?: roots.lastOrNull() ?: decor
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
