package gg.padu.httpmonitor.report

import android.util.Log
import gg.padu.httpmonitor.HttpTransaction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ships captured traffic to an Antoo Spector dashboard.
 *
 * ```
 * HttpMonitor
 *     .start(maxTransactions = 500)
 *     .addFilter(HeaderRedactingFilter("Authorization"))
 *     .report(
 *         AntooReporter(
 *             endpoint = "https://spector.example.com/api/v1/ingest",
 *             apiKey = BuildConfig.ANTOO_KEY,
 *             device = DeviceInfo.from(this)
 *         )
 *     )
 * ```
 *
 * Transactions are queued as they are captured and posted in batches from a
 * single background thread. Filters run before the reporter sees anything, so
 * whatever a [gg.padu.httpmonitor.HttpFilter] redacted is redacted here too.
 *
 * Nothing about it is fatal: a queue that outruns the network drops its oldest
 * entries, and a failing upload is retried with backoff rather than propagated.
 */
class AntooReporter @JvmOverloads constructor(
    private val endpoint: String,
    private val apiKey: String,
    private val device: DeviceInfo,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS,
    private val client: OkHttpClient = defaultClient(),
    /**
     * How stale contact may get before the reporter says it is still here. An
     * empty batch is that statement — the smallest thing the dashboard accepts —
     * and it rides the flush the reporter already wakes for, so an idle app costs
     * one short POST per interval and no extra wake-ups. Zero turns it off, and
     * the dashboard then judges a device by the last traffic it captured.
     */
    private val heartbeatIntervalMs: Long = flushIntervalMs
) : TransactionReporter {

    private val lock = Any()
    private val queue = ArrayDeque<HttpTransaction>()
    private val closed = AtomicBoolean(false)
    private val sessionBase = TransactionJson.sessionBase(System.currentTimeMillis())

    /** Guarded by [lock]; backs off the upload thread after repeated failures. */
    private var consecutiveFailures = 0
    private var nextAttemptAt = 0L

    /** Guarded by [lock]; when the dashboard last heard anything from us. */
    private var lastContactAt = 0L

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "antoo-reporter").apply { isDaemon = true }
        }

    init {
        executor.scheduleWithFixedDelay(
            ::drain, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS
        )
    }

    override fun report(transaction: HttpTransaction) {
        if (closed.get() || isOwnTraffic(transaction)) return

        val full = synchronized(lock) {
            queue.addLast(transaction)
            while (queue.size > queueCapacity) queue.removeFirst()
            queue.size >= batchSize
        }

        if (full) submit()
    }

    override fun flush() {
        if (!closed.get()) submit()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        executor.execute(::drain)
        executor.shutdown()
        runCatching { executor.awaitTermination(flushIntervalMs, TimeUnit.MILLISECONDS) }
    }

    /** The reporter's own uploads must never become traffic to upload. */
    private fun isOwnTraffic(transaction: HttpTransaction): Boolean =
        transaction.request.url.startsWith(endpoint)

    private fun submit() {
        runCatching { executor.execute(::drain) }
    }

    private fun drain() {
        if (System.currentTimeMillis() < synchronized(lock) { nextAttemptAt }) return

        while (true) {
            val batch = synchronized(lock) {
                if (queue.isEmpty()) null
                else List(minOf(batchSize, queue.size)) { queue.removeFirst() }
            } ?: break

            when (send(batch)) {
                Outcome.SENT -> synchronized(lock) {
                    consecutiveFailures = 0
                    nextAttemptAt = 0L
                    lastContactAt = System.currentTimeMillis()
                }

                // Keep the batch, at the front, and stop until the backoff expires.
                Outcome.RETRY -> {
                    synchronized(lock) {
                        batch.asReversed().forEach { queue.addFirst(it) }
                        while (queue.size > queueCapacity) queue.removeLast()

                        consecutiveFailures++
                        val backoff = (flushIntervalMs shl minOf(consecutiveFailures, MAX_BACKOFF_SHIFT))
                            .coerceAtMost(MAX_BACKOFF_MS)
                        nextAttemptAt = System.currentTimeMillis() + backoff
                    }
                    return
                }

                // The server will never accept it — a bad key, or a malformed batch.
                // Retrying would wedge the queue behind it, so let it go.
                Outcome.DROP -> synchronized(lock) { consecutiveFailures = 0 }
            }
        }

        beat()
    }

    /**
     * Tells the dashboard the app is still running when there is no traffic to
     * report, so a quiet device reads as online rather than gone. Sent only once
     * contact has gone stale, and never instead of a batch — a batch says the
     * same thing and carries data with it.
     */
    private fun beat() {
        if (heartbeatIntervalMs <= 0) return

        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            now >= nextAttemptAt && now - lastContactAt >= heartbeatIntervalMs
        }
        if (!due) return

        // A failed heartbeat is not data lost, so it neither retries nor backs
        // off: the next interval comes round soon enough.
        val outcome = send(emptyList())
        synchronized(lock) {
            lastContactAt = if (outcome == Outcome.SENT) now else now - heartbeatIntervalMs / 2
        }
    }

    private fun send(batch: List<HttpTransaction>): Outcome {
        val payload = runCatching {
            TransactionJson.batch(
                device,
                batch.map {
                    TransactionJson.encode(
                        transaction = it,
                        clientId = TransactionJson.clientId(sessionBase, it.id),
                        maxBodyChars = maxBodyChars
                    )
                }
            ).toString()
        }.getOrElse { error ->
            Log.w(TAG, "Could not encode a batch of ${batch.size}; dropping it", error)
            return Outcome.DROP
        }

        val what = if (batch.isEmpty()) "heartbeat" else "batch of ${batch.size}"

        val request = Request.Builder()
            .url(endpoint)
            .header("X-Antoo-Key", apiKey)
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Outcome.SENT
                    response.code == 429 || response.code >= 500 -> Outcome.RETRY
                    else -> {
                        Log.w(TAG, "Dashboard refused a $what: HTTP ${response.code}")
                        Outcome.DROP
                    }
                }
            }
        } catch (error: IOException) {
            Log.d(TAG, "Sending a $what failed: ${error.message}")
            Outcome.RETRY
        }
    }

    private enum class Outcome { SENT, RETRY, DROP }

    companion object {

        const val DEFAULT_BATCH_SIZE = 50

        const val DEFAULT_FLUSH_INTERVAL_MS = 15_000L

        const val DEFAULT_QUEUE_CAPACITY = 500

        /** Bodies are capped well below the server's limit; this rides mobile data. */
        const val DEFAULT_MAX_BODY_CHARS = 16_384

        /** Turns heartbeats off, so only captured traffic marks a device alive. */
        const val HEARTBEAT_OFF = 0L

        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val MAX_BACKOFF_SHIFT = 5
        private const val TAG = "AntooReporter"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Deliberately not the app's own client: an instrumented one would capture
         * these uploads, and each upload would produce more to upload.
         */
        @JvmStatic
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
