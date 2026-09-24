package gg.padu.httpmonitor.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import gg.padu.httpmonitor.R
import gg.padu.httpmonitor.HttpMonitor
import gg.padu.httpmonitor.HttpTransaction
import gg.padu.httpmonitor.TransactionStore

/** List of captured transactions, newest first, with a live-updating text filter. */
class HttpMonitorActivity : AppCompatActivity() {

    private lateinit var adapter: TransactionAdapter
    private lateinit var summary: TextView
    private lateinit var empty: TextView

    private var captured: List<HttpTransaction> = emptyList()
    private var query: String = ""

    private val listener = TransactionStore.Listener { transactions ->
        captured = transactions
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.http_monitor_activity_list)
        title = getString(R.string.http_monitor_title)

        summary = findViewById(R.id.summary)
        empty = findViewById(R.id.empty)
        adapter = TransactionAdapter { transaction ->
            startActivity(
                Intent(this, TransactionDetailActivity::class.java)
                    .putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ID, transaction.id)
            )
        }

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@HttpMonitorActivity)
            adapter = this@HttpMonitorActivity.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        findViewById<Button>(R.id.clear).setOnClickListener { HttpMonitor.clear() }
        findViewById<EditText>(R.id.filter).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                render()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        HttpMonitor.addListener(listener)
    }

    override fun onStop() {
        super.onStop()
        HttpMonitor.removeListener(listener)
    }

    private fun render() {
        val visible = captured.filter { it.matches(query) }
        adapter.submitList(visible)
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        summary.text = if (query.isEmpty()) {
            "${captured.size} requests"
        } else {
            "${visible.size} of ${captured.size} requests"
        }
    }

    private fun HttpTransaction.matches(query: String): Boolean {
        if (query.isEmpty()) return true
        return request.url.contains(query, ignoreCase = true) ||
            request.method.equals(query, ignoreCase = true) ||
            statusCode.toString().startsWith(query)
    }
}
