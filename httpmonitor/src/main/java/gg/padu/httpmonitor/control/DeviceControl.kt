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
import java.util.concurrent.atomic.AtomicReference

/**
 * The device half of Device Control. It polls the dashboard for work queued
 * against this install, runs it, and uploads the result — over the same ingest
 * key the reporter already uses.
 *
 * The one action so far is a screenshot, and it is a screenshot of *this app's*
 * window: [ScreenCapture] reads the foreground Activity's own surface, which is
 * always the app's to read, so nothing is prompted and nothing beyond the app is
 * seen. An install with no Activity in the foreground has nothing to capture and
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
        }.also {
            it.scheduleWithFixedDelay(::poll, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() = apply {
        if (!started.compareAndSet(true, false)) return@apply

        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        executor?.shutdownNow()
        captureThread?.quitSafely()
        executor = null
        captureThread = null
        capture = null
        foreground.set(WeakReference(null))
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

        for (command in commands) {
            when (command.type) {
                ControlProtocol.TYPE_SCREENSHOT -> runScreenshot(command.id)
                else -> reportFailure(command.id, "Unsupported command: ${command.type}")
            }
        }
    }

    private fun runScreenshot(commandId: Long) {
        val activity = foreground.get().get()
        if (activity == null) {
            reportFailure(commandId, "No screen is in the foreground.")
            return
        }

        val shot = runCatching {
            capture?.capture(activity, maxDimension, jpegQuality, CAPTURE_TIMEOUT_MS)
        }.getOrNull()

        if (shot == null) {
            reportFailure(commandId, "The screen could not be captured.")
            return
        }

        upload(commandId, shot)
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

        const val DEFAULT_POLL_INTERVAL_MS = 3_000L

        /** The longest side of an uploaded screenshot; clear, not crisp. */
        const val DEFAULT_MAX_DIMENSION = 720

        const val DEFAULT_JPEG_QUALITY = 60

        private const val CAPTURE_TIMEOUT_MS = 4_000L
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
            client: OkHttpClient = defaultClient()
        ): DeviceControl = DeviceControl(
            context = context,
            controlBase = ControlProtocol.controlBase(endpoint),
            apiKey = apiKey,
            deviceUid = device.uid,
            pollIntervalMs = pollIntervalMs,
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
