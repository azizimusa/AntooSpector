package gg.padu.httpmonitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import gg.padu.httpmonitor.R
import gg.padu.httpmonitor.Bodies
import gg.padu.httpmonitor.Headers
import gg.padu.httpmonitor.HttpMonitor
import gg.padu.httpmonitor.HttpTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full request and response of one transaction, with JSON/XML formatting. */
class TransactionDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.http_monitor_activity_detail)

        val transaction = HttpMonitor.find(intent.getLongExtra(EXTRA_TRANSACTION_ID, -1))
        if (transaction == null) {
            Toast.makeText(this, "Transaction is no longer available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        title = "${transaction.request.method} ${transaction.path}"
        findViewById<TextView>(R.id.url).text = transaction.request.url
        findViewById<TextView>(R.id.overview).text = overviewOf(transaction)
        findViewById<TextView>(R.id.requestHeaders).text =
            section("REQUEST HEADERS", transaction.request.headers)
        findViewById<TextView>(R.id.requestBody).text = section(
            "REQUEST BODY",
            Bodies.asText(
                transaction.request.body,
                transaction.request.contentType,
                transaction.request.bodyTruncated
            )
        )
        findViewById<TextView>(R.id.responseHeaders).text =
            section("RESPONSE HEADERS", transaction.response?.headers)
        findViewById<TextView>(R.id.responseBody).text = section(
            "RESPONSE BODY",
            transaction.response?.let {
                Bodies.asText(it.body, it.contentType, it.bodyTruncated)
            }.orEmpty()
        )

        findViewById<Button>(R.id.copyCurl).setOnClickListener {
            getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("cURL", Bodies.asCurl(transaction)))
            Toast.makeText(this, "Copied as cURL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun overviewOf(transaction: HttpTransaction): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(transaction.request.startedAt))
        return buildString {
            appendLine("Status      ${transaction.error ?: "${transaction.statusCode} ${transaction.response?.statusMessage.orEmpty()}".trim()}")
            appendLine("Method      ${transaction.request.method}")
            appendLine("Started     $time")
            appendLine("Duration    ${transaction.durationMs} ms")
            appendLine("Request     ${Bodies.formatSize(transaction.request.bodySize)}")
            appendLine("Response    ${Bodies.formatSize(transaction.response?.bodySize ?: 0)}")
            append("Source      ${transaction.source.name}")
        }
    }

    private fun section(title: String, headers: Headers?): String =
        section(title, headers?.toString().orEmpty())

    private fun section(title: String, body: String): String =
        "$title\n${body.ifBlank { "(empty)" }}"

    companion object {
        const val EXTRA_TRANSACTION_ID = "gg.padu.httpmonitor.TRANSACTION_ID"
    }
}
