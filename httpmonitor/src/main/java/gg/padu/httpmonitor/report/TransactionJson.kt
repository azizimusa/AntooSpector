package gg.padu.httpmonitor.report

import gg.padu.httpmonitor.Bodies
import gg.padu.httpmonitor.Headers
import gg.padu.httpmonitor.HttpTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Builds the JSON the dashboard's `POST /api/v1/ingest` expects. Kept apart from
 * the transport so the wire shape can be asserted on without a server.
 */
object TransactionJson {

    /**
     * Ids are unique per (install, transaction) on the server, but
     * `HttpMonitor.nextId()` restarts at 1 with the process — so two launches of
     * the same install would overwrite each other's first rows. Offsetting by the
     * second this session started keeps them distinct, provided one session stays
     * under [IDS_PER_SESSION] transactions (the store's default ceiling is 500).
     */
    const val IDS_PER_SESSION = 1_000_000L

    fun sessionBase(startedAtMillis: Long): Long = startedAtMillis / 1_000L * IDS_PER_SESSION

    fun clientId(sessionBase: Long, transactionId: Long): Long =
        sessionBase + Math.floorMod(transactionId, IDS_PER_SESSION)

    fun batch(device: DeviceInfo, transactions: List<JSONObject>): JSONObject = JSONObject().apply {
        put("device", device.toJson())
        put("transactions", JSONArray(transactions))
    }

    fun encode(transaction: HttpTransaction, clientId: Long, maxBodyChars: Int): JSONObject =
        JSONObject().apply {
            put("id", clientId)
            put("source", transaction.source.name.lowercase(Locale.US))
            put("duration_ms", transaction.durationMs)
            transaction.error?.let { put("error", it) }

            put("request", JSONObject().apply {
                put("method", transaction.request.method)
                put("url", transaction.request.url)
                put("headers", headers(transaction.request.headers))
                put("body_size", transaction.request.bodySize)
                put("body_truncated", transaction.request.bodyTruncated)
                put("started_at", transaction.request.startedAt)
                body(transaction.request.body, transaction.request.contentType, maxBodyChars)
                    ?.let { put("body", it) }
            })

            transaction.response?.let { response ->
                put("response", JSONObject().apply {
                    put("status_code", response.statusCode)
                    put("status_message", response.statusMessage)
                    put("headers", headers(response.headers))
                    put("body_size", response.bodySize)
                    put("body_truncated", response.bodyTruncated)
                    put("received_at", response.receivedAt)
                    body(response.body, response.contentType, maxBodyChars)
                        ?.let { put("body", it) }
                })
            }
        }

    /** The multi-value shape the server prefers: name -> [values]. */
    private fun headers(headers: Headers): JSONObject = JSONObject().apply {
        headers.names().forEach { name -> put(name, JSONArray(headers.all(name))) }
    }

    /**
     * Binary payloads are described rather than sent — the dashboard renders text,
     * and base64 of a bitmap would only bloat the batch.
     */
    private fun body(bytes: ByteArray?, contentType: String?, maxBodyChars: Int): String? {
        if (bytes == null || bytes.isEmpty()) return null

        if (!Bodies.isTextual(contentType)) {
            return "<binary body, ${Bodies.formatSize(bytes.size.toLong())}>"
        }

        val text = String(bytes, Bodies.charsetOf(contentType))
        return if (text.length > maxBodyChars) {
            text.take(maxBodyChars) + "\n… truncated by the reporter …"
        } else {
            text
        }
    }
}
