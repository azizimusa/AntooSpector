package gg.padu.httpmonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import gg.padu.httpmonitor.R
import gg.padu.httpmonitor.Bodies
import gg.padu.httpmonitor.HttpTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class TransactionAdapter(
    private val onClick: (HttpTransaction) -> Unit
) : ListAdapter<HttpTransaction, TransactionAdapter.ViewHolder>(DIFF) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.http_monitor_item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val status: TextView = itemView.findViewById(R.id.status)
        private val path: TextView = itemView.findViewById(R.id.path)
        private val host: TextView = itemView.findViewById(R.id.host)
        private val meta: TextView = itemView.findViewById(R.id.meta)

        fun bind(transaction: HttpTransaction) {
            status.text = if (transaction.isFailed) "!" else transaction.statusCode.toString()
            status.setTextColor(ContextCompat.getColor(itemView.context, colorFor(transaction)))
            path.text = "${transaction.request.method}  ${transaction.path}"
            host.text = transaction.host.orEmpty()
            meta.text = listOf(
                timeFormat.format(Date(transaction.request.startedAt)),
                "${transaction.durationMs} ms",
                Bodies.formatSize(transaction.totalSize)
            ).joinToString("  ·  ")
            itemView.setOnClickListener { onClick(transaction) }
        }

        private fun colorFor(transaction: HttpTransaction): Int = when {
            transaction.isFailed -> R.color.http_monitor_status_failed
            transaction.statusCode >= 500 -> R.color.http_monitor_status_server_error
            transaction.statusCode >= 400 -> R.color.http_monitor_status_client_error
            transaction.statusCode >= 300 -> R.color.http_monitor_status_redirect
            transaction.statusCode >= 200 -> R.color.http_monitor_status_success
            else -> R.color.http_monitor_secondary
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<HttpTransaction>() {
            override fun areItemsTheSame(old: HttpTransaction, new: HttpTransaction) = old.id == new.id
            // A recorded transaction never changes, so equal ids mean equal contents.
            override fun areContentsTheSame(old: HttpTransaction, new: HttpTransaction) = old.id == new.id
        }
    }
}
