package gg.padu.httpmonitor.control

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import gg.padu.httpmonitor.report.DeviceInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The device half of Device Control. It polls the dashboard for work queued
 * against this install, runs it, and uploads the result — over the same ingest
 * key the reporter already uses.
 *
 * Two actions, both confined to *this app's* own windows:
 *
 *  - **screenshot** — [ScreenCapture] reads the foreground Activity's own
 *    surface, which is always the app's to read, so nothing is prompted and
 *    nothing beyond the app is seen.
 *  - **tap**, **key** and **text** — [RemoteInput] delivers a touch to the window
 *    under a point on the last screenshot, a key press (Back, Enter, Backspace)
 *    to the focused window, or typed text into whatever holds focus. Each answers
 *    with a fresh screenshot, so the dashboard shows what it did. Also
 *    permission-free, and also limited to the app: the system UI, Home, Recents
 *    and other apps are not this app's windows.
 *
 * An install with no Activity in the foreground has nothing to capture or tap and
 * says so.
 *
 * ```
 * DeviceControl
 *     .from(this, endpoint = "https://spector.example.com/api/v1/ingest",
 *           apiKey = BuildConfig.ANTOO_KEY, device = DeviceInfo.from(this))
 *     .start()
 * ```
 *
 * Nothing here is fatal: a failed poll or upload is logged and left for the next
 * turn, and a command the device never collected expires on the server.
 */
class DeviceControl private constructor(
    context: Context,
    private val controlBase: String,
    private val apiKey: String,
    private val deviceUid: String,
    private val pollIntervalMs: Long,
    private val busyPollIntervalMs: Long,
    private val tapSettleMs: Long,
    private val maxDimension: Int,
    private val jpegQuality: Int,
    private val client: OkHttpClient
) {

    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)

    /** The Activity to capture: whatever is resumed, held weakly so it can die. */
    private val foreground = AtomicReference<WeakReference<Activity>>(WeakReference(null))

    private var executor: ScheduledExecutorService? = null
    private var captureThread: HandlerThread? = null
    private var capture: ScreenCapture? = null
    private val input = RemoteInput()

    /**
     * How long the quick poll interval stays in force. Someone driving the app
     * from the dashboard sends a run of commands, so the first one switches the
     * loop to a short delay and each one after it extends that — a tap answering
     * in half a second is worth the traffic, and an install nobody is watching
     * goes straight back to the idle interval.
     */
    private val busyUntil = AtomicLong(0L)

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            foreground.set(WeakReference(activity))
        }

        override fun onActivityPaused(activity: Activity) {
            if (foreground.get().get() === activity) foreground.set(WeakReference(null))
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun start() = apply {
        if (!started.compareAndSet(false, true)) return@apply

        (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
            ?: Log.w(TAG, "Context is not an Application; screenshots will find no window")

        val thread = HandlerThread("antoo-control-capture").also { it.start() }
        captureThread = thread
        capture = ScreenCapture(Handler(thread.looper))

        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "antoo-control").apply { isDaemon = true }
        }

        schedule(pollIntervalMs)
    }

    fun stop() = apply {
        if (!started.compareAndSet(true, false)) return@apply

        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        executor?.shutdownNow()
        captureThread?.quitSafely()
        executor = null
        captureThread = null
        capture = null
        busyUntil.set(0L)
        foreground.set(WeakReference(null))
    }

    /**
     * One turn of the loop, rescheduling itself rather than running at a fixed
     * delay — the interval depends on whether anyone is driving this install.
     */
    private fun turn() {
        runCatching { poll() }
        schedule(if (System.currentTimeMillis() < busyUntil.get()) busyPollIntervalMs else pollIntervalMs)
    }

    private fun schedule(delayMs: Long) {
        if (!started.get()) return

        // A shutdown between the check and here rejects the task; stopping is not
        // an error, so the rejection is simply the end of the loop.
        runCatching { executor?.schedule(::turn, delayMs, TimeUnit.MILLISECONDS) }
    }

    private fun poll() {
        val commands = runCatching {
            val request = Request.Builder()
                .url(ControlProtocol.commandsUrl(controlBase, deviceUid))
                .header("X-Antoo-Key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                ControlProtocol.parseCommands(response.body?.string().orEmpty())
            }
        }.getOrElse { error ->
            Log.d(TAG, "Polling for commands failed: ${error.message}")
            return
        }

        if (commands.isNotEmpty()) busyUntil.set(System.currentTimeMillis() + BUSY_WINDOW_MS)

        for (command in commands) {
            when (command.type) {
                ControlProtocol.TYPE_SCREENSHOT -> runScreenshot(command.id)
                ControlProtocol.TYPE_TAP -> runTap(command)
                ControlProtocol.TYPE_KEY -> runKey(command)
                ControlProtocol.TYPE_TEXT -> runText(command)
                else -> reportFailure(command.id, "Unsupported command: ${command.type}")
            }
        }
    }

    private fun runScreenshot(commandId: Long) {
        if (foreground.get().get() == null) {
            reportFailure(commandId, "No screen is in the foreground.")
            return
        }

        val shot = grab()
        if (shot == null) {
            reportFailure(commandId, "The screen could not be captured.")
            return
        }

        upload(commandId, shot)
    }

    /**
     * A tap, answered with a screenshot of what it did.
     */
    private fun runTap(command: ControlProtocol.Command) {
        val tap = ControlProtocol.parseTap(command.params)
        if (tap == null) {
            reportFailure(command.id, "The tap had no usable coordinates.")
            return
        }

        act(command, "tap") { activity -> input.tap(activity, tap, INPUT_TIMEOUT_MS) }
    }

    /** One key press — Back, Enter, Backspace — on the app's focused window. */
    private fun runKey(command: ControlProtocol.Command) {
        val keyCode = ControlProtocol.parseKey(command.params)
        if (keyCode == null) {
            reportFailure(command.id, "That key is not one this app can press.")
            return
        }

        act(command, "key press") { activity -> input.key(activity, keyCode, INPUT_TIMEOUT_MS) }
    }

    /** Text typed into whatever has focus, as key strokes rather than through an IME. */
    private fun runText(command: ControlProtocol.Command) {
        val text = ControlProtocol.parseText(command.params)
        if (text == null) {
            reportFailure(command.id, "There was no text to type.")
            return
        }

        act(command, "text") { activity -> input.text(activity, text, INPUT_TIMEOUT_MS) }
    }

    /**
     * Every interactive command runs the same way: do it to the app, let the app
     * react, then answer with a screenshot of what it produced. The command
     * carries the image back itself — an action whose result you cannot see is
     * not much use, and a separate screenshot command would race the reaction.
     */
    private fun act(command: ControlProtocol.Command, name: String, perform: (Activity) -> Boolean) {
        val activity = foreground.get().get()
        if (activity == null) {
            reportFailure(command.id, "No screen is in the foreground.")
            return
        }

        val landed = runCatching { perform(activity) }.getOrDefault(false)
        if (!landed) {
            reportFailure(command.id, "The $name could not be delivered to the app's window.")
            return
        }

        // Let the app react before looking: an action that opens a screen or a
        // dialog needs a frame or two, and a screenshot taken mid-transition
        // shows neither where it was nor where it went.
        runCatching { Thread.sleep(tapSettleMs) }

        val shot = grab()
        if (shot == null) {
            // Said plainly, because the two halves failed differently: the app did
            // get the $name, so the dashboard must not offer to send it again.
            reportFailure(command.id, "The $name landed, but the screen could not be captured.")
            return
        }

        upload(command.id, shot)
    }

    /**
     * The current screen, or null. Read fresh each time — a tap may have moved
     * the app to another Activity entirely, and that new screen is the answer.
     */
    private fun grab(): ScreenCapture.Shot? {
        val activity = foreground.get().get() ?: return null

        return runCatching {
            capture?.capture(activity, maxDimension, jpegQuality, CAPTURE_TIMEOUT_MS)
        }.getOrNull()
    }

    private fun upload(commandId: Long, shot: ScreenCapture.Shot) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "screenshot.jpg", shot.bytes.toRequestBody(JPEG))
            .addFormDataPart("width", shot.width.toString())
            .addFormDataPart("height", shot.height.toString())
            .addFormDataPart("captured_at", System.currentTimeMillis().toString())
            .build()

        val request = Request.Builder()
            .url(ControlProtocol.captureUrl(controlBase, commandId))
            .header("X-Antoo-Key", apiKey)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Dashboard refused a screenshot: HTTP ${response.code}")
                }
            }
        } catch (error: IOException) {
            // The command stays dispatched and expires on the server; no retry, a
            // stale screenshot is worse than none.
            Log.d(TAG, "Uploading a screenshot failed: ${error.message}")
        }
    }

    private fun reportFailure(commandId: Long, reason: String) {
        val request = Request.Builder()
            .url(ControlProtocol.failUrl(controlBase, commandId))
            .header("X-Antoo-Key", apiKey)
            .post("""{"error":${org.json.JSONObject.quote(reason)}}""".toRequestBody(JSON))
            .build()

        runCatching { client.newCall(request).execute().close() }
    }

    companion object {

        /** How often an install nobody is driving asks whether there is work. */
        const val DEFAULT_POLL_INTERVAL_MS = 3_000L

        /**
         * The interval while someone is driving: a tap is only as responsive as
         * the poll that collects it, and half a second is the difference between
         * remote control and a form submission.
         */
        const val DEFAULT_BUSY_POLL_INTERVAL_MS = 500L

        /** How long the app is given to react to a tap before it is photographed. */
        const val DEFAULT_TAP_SETTLE_MS = 450L

        /**
         * The longest side of an uploaded screenshot. 1080 keeps a phone screen
         * legible — text stays sharp — while still downscaling most panels; raise
         * it toward the device's own height for a pixel-exact capture, at the cost
         * of a larger upload.
         */
        const val DEFAULT_MAX_DIMENSION = 1080

        /** JPEG quality of the upload. 85 is clean for UI and text without the bloat of 100. */
        const val DEFAULT_JPEG_QUALITY = 85

        private const val CAPTURE_TIMEOUT_MS = 4_000L
        private const val INPUT_TIMEOUT_MS = 2_000L

        /** Quiet for this long after the last command and the loop idles again. */
        private const val BUSY_WINDOW_MS = 20_000L
        private const val TAG = "DeviceControl"

        private val JPEG = "image/jpeg".toMediaType()
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Builds a controller from the same ingest endpoint and key the reporter
         * uses; the control URLs sit one path over from ingest.
         */
        @JvmStatic
        @JvmOverloads
        fun from(
            context: Context,
            endpoint: String,
            apiKey: String,
            device: DeviceInfo,
            pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
            maxDimension: Int = DEFAULT_MAX_DIMENSION,
            jpegQuality: Int = DEFAULT_JPEG_QUALITY,
            client: OkHttpClient = defaultClient(),
            // Appended rather than slotted in beside the other intervals: callers
            // pass these positionally from Java, and a new parameter in the middle
            // would quietly turn someone's `maxDimension` into a poll interval.
            busyPollIntervalMs: Long = DEFAULT_BUSY_POLL_INTERVAL_MS,
            tapSettleMs: Long = DEFAULT_TAP_SETTLE_MS
        ): DeviceControl = DeviceControl(
            context = context,
            controlBase = ControlProtocol.controlBase(endpoint),
            apiKey = apiKey,
            deviceUid = device.uid,
            pollIntervalMs = pollIntervalMs,
            busyPollIntervalMs = busyPollIntervalMs,
            tapSettleMs = tapSettleMs,
            maxDimension = maxDimension,
            jpegQuality = jpegQuality,
            client = client
        )

        /**
         * Its own client, never the app's instrumented one: these polls and
         * uploads must not become traffic the reporter captures.
         */
        @JvmStatic
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
