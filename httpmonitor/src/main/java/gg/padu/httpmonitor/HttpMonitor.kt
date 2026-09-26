package gg.padu.httpmonitor

import android.content.Context
import android.content.Intent
import gg.padu.httpmonitor.report.TransactionReporter
import gg.padu.httpmonitor.ui.HttpMonitorActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Entry point of the HTTP monitor. Capture is opt-in per client: install
 * [gg.padu.httpmonitor.okhttp.HttpMonitorInterceptor] on an OkHttpClient, or open
 * connections through [gg.padu.httpmonitor.urlconnection.UrlInstrument].
 *
 * ```
 * HttpMonitor.start(maxTransactions = 500)
 *     .addFilter(HeaderRedactingFilter("Authorization"))
 * ```
 *
 * Pass a [TransactionReporter] to [report] to also ship what is captured to a
 * server; see [gg.padu.httpmonitor.report.AntooReporter].
 */
object HttpMonitor {

    const val DEFAULT_MAX_TRANSACTIONS = 500

    /** Payload bytes retained per request and per response; the rest is counted, not stored. */
    const val DEFAULT_MAX_BODY_BYTES = 512L * 1024L

    private val ids = AtomicLong()
    private val filters = CopyOnWriteArrayList<HttpFilter>()

    @Volatile
    private var store: TransactionStore = TransactionStore(DEFAULT_MAX_TRANSACTIONS)

    @Volatile
    private var reporter: TransactionReporter? = null

    /** Capture is a no-op while this is false; the store keeps whatever it already holds. */
    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    fun start(
        maxTransactions: Int = DEFAULT_MAX_TRANSACTIONS,
        maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES
    ) = apply {
        store = TransactionStore(maxTransactions)
        this.maxBodyBytes = maxBodyBytes
        isEnabled = true
    }

    fun stop() = apply { isEnabled = false }

    /**
     * Installs the reporter that captured traffic is shipped to, replacing and
     * closing any previous one. Pass null to stop reporting.
     */
    fun report(reporter: TransactionReporter?) = apply {
        this.reporter.takeIf { it !== reporter }?.let { previous -> runCatching { previous.close() } }
        this.reporter = reporter
    }

    /** Asks the installed reporter to send what it has queued, if any. */
    fun flushReports() = apply { runCatching { reporter?.flush() } }

    fun addFilter(filter: HttpFilter) = apply { filters.add(filter) }

    fun removeFilter(filter: HttpFilter) = apply { filters.remove(filter) }

    fun nextId(): Long = ids.incrementAndGet()

    fun transactions(): List<HttpTransaction> = store.snapshot()

    fun find(id: Long): HttpTransaction? = store.find(id)

    fun clear() = store.clear()

    fun addListener(listener: TransactionStore.Listener) = store.addListener(listener)

    fun removeListener(listener: TransactionStore.Listener) = store.removeListener(listener)

    /** Opens the built-in viewer. */
    fun show(context: Context) {
        context.startActivity(
            Intent(context, HttpMonitorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Runs the configured filters and stores the exchange. Returns the stored transaction,
     * or null when capture is off or a filter dropped it.
     */
    fun record(
        id: Long,
        source: HttpSource,
        request: HttpRequest,
        response: HttpResponse?,
        error: String? = null
    ): HttpTransaction? {
        if (!isEnabled) return null

        var filteredRequest: HttpRequest = request
        var filteredResponse: HttpResponse? = response
        for (filter in filters) {
            filteredRequest = filter.filter(filteredRequest) ?: return null
            if (filteredResponse != null) {
                filteredResponse = filter.filter(filteredResponse) ?: return null
            }
        }

        return HttpTransaction(id, source, filteredRequest, filteredResponse, error)
            .also { transaction ->
                store.record(transaction)
                // A misbehaving reporter must never break the call being made.
                runCatching { reporter?.report(transaction) }
            }
    }
}
