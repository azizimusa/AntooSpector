package gg.padu.httpmonitor

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory ring buffer of captured transactions, newest first. */
class TransactionStore(private val capacity: Int) {

    fun interface Listener {
        fun onTransactionsChanged(transactions: List<HttpTransaction>)
    }

    private val lock = Any()
    private val transactions = ArrayDeque<HttpTransaction>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun record(transaction: HttpTransaction) {
        synchronized(lock) {
            transactions.addFirst(transaction)
            while (transactions.size > capacity) transactions.removeLast()
        }
        notifyListeners()
    }

    fun snapshot(): List<HttpTransaction> = synchronized(lock) { transactions.toList() }

    fun find(id: Long): HttpTransaction? = synchronized(lock) { transactions.firstOrNull { it.id == id } }

    fun clear() {
        synchronized(lock) { transactions.clear() }
        notifyListeners()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onTransactionsChanged(snapshot())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val current = snapshot()
        mainHandler.post { listeners.forEach { it.onTransactionsChanged(current) } }
    }
}
