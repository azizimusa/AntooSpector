package gg.padu.httpmonitor.report

import gg.padu.httpmonitor.HttpTransaction

/**
 * Receives every transaction the monitor stores, so it can be shipped somewhere
 * outside the process. Installed with [gg.padu.httpmonitor.HttpMonitor.report].
 *
 * [report] is called on the thread that made the HTTP call, so implementations
 * must only hand the transaction off — never block, and never throw.
 */
interface TransactionReporter {

    fun report(transaction: HttpTransaction)

    /** Sends whatever is queued now, rather than waiting for the next interval. */
    fun flush() {}

    /** Releases the reporter's threads. Further [report] calls are ignored. */
    fun close() {}
}
