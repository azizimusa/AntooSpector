package gg.padu.httpmonitor.urlconnection

import gg.padu.httpmonitor.Headers
import gg.padu.httpmonitor.HttpMonitor
import gg.padu.httpmonitor.HttpRequest
import gg.padu.httpmonitor.HttpResponse
import gg.padu.httpmonitor.HttpSource
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects one `HttpURLConnection` exchange. The connection wrappers feed it; the transaction is
 * stored once the response body is exhausted or the connection is disconnected — whichever is first.
 */
internal class UrlConnectionRecorder(private val delegate: HttpURLConnection) {

    private val id = HttpMonitor.nextId()
    private val startedAt = System.currentTimeMillis()
    private val finished = AtomicBoolean(false)

    private val requestBytes = ByteArrayOutputStream()
    private var requestSize = 0L
    private val responseBytes = ByteArrayOutputStream()
    private var responseSize = 0L

    private var statusCode = -1
    private var statusMessage = ""
    private var responseHeaders: Headers? = null
    private var receivedAt = 0L
    private var error: String? = null

    fun wrapOutput(stream: OutputStream): OutputStream = object : FilterOutputStream(stream) {
        override fun write(b: Int) {
            capture(byteArrayOf(b.toByte()), 0, 1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            capture(b, off, len)
            out.write(b, off, len)
        }

        private fun capture(bytes: ByteArray, off: Int, len: Int) {
            requestSize += len
            val room = (HttpMonitor.maxBodyBytes - requestBytes.size()).coerceAtLeast(0)
            if (room > 0) requestBytes.write(bytes, off, minOf(len.toLong(), room).toInt())
        }
    }

    fun wrapInput(stream: InputStream?): InputStream? {
        if (stream == null) return null
        return object : FilterInputStream(stream) {
            override fun read(): Int = `in`.read().also { value ->
                if (value == -1) finish() else capture(byteArrayOf(value.toByte()), 0, 1)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { count ->
                if (count == -1) finish() else capture(b, off, count)
            }

            override fun close() {
                finish()
                `in`.close()
            }

            private fun capture(bytes: ByteArray, off: Int, len: Int) {
                responseSize += len
                val room = (HttpMonitor.maxBodyBytes - responseBytes.size()).coerceAtLeast(0)
                if (room > 0) responseBytes.write(bytes, off, minOf(len.toLong(), room).toInt())
            }
        }
    }

    fun onResponse(code: Int, message: String?) {
        statusCode = code
        statusMessage = message.orEmpty()
        receivedAt = System.currentTimeMillis()
        responseHeaders = runCatching { Headers(delegate.headerFields) }.getOrDefault(Headers())
    }

    fun onFailure(throwable: Throwable) {
        error = throwable.toString()
        finish()
    }

    /** Idempotent: the first of stream-EOF, stream-close or `disconnect()` wins. */
    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        if (!HttpMonitor.isEnabled) return

        val request = HttpRequest(
            method = runCatching { delegate.requestMethod }.getOrDefault("GET"),
            url = delegate.url.toString(),
            headers = runCatching { Headers(delegate.requestProperties) }.getOrDefault(Headers()),
            body = requestBytes.toByteArray().takeIf { it.isNotEmpty() },
            bodySize = requestSize,
            bodyTruncated = requestSize > requestBytes.size(),
            startedAt = startedAt
        )

        val response = if (statusCode >= 0) {
            HttpResponse(
                statusCode = statusCode,
                statusMessage = statusMessage,
                headers = responseHeaders ?: Headers(),
                body = responseBytes.toByteArray().takeIf { it.isNotEmpty() },
                bodySize = responseSize,
                bodyTruncated = responseSize > responseBytes.size(),
                receivedAt = System.currentTimeMillis()
            )
        } else {
            null
        }

        HttpMonitor.record(id, HttpSource.URL_CONNECTION, request, response, error)
    }
}
