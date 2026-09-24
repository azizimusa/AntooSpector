package gg.padu.httpmonitor.okhttp

import gg.padu.httpmonitor.Headers
import gg.padu.httpmonitor.HttpMonitor
import gg.padu.httpmonitor.HttpRequest
import gg.padu.httpmonitor.HttpResponse
import gg.padu.httpmonitor.HttpSource
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Captures traffic of an OkHttpClient. Add it as an *application* interceptor so bodies are
 * seen decompressed and redirects appear as the call the app actually made:
 *
 * ```
 * OkHttpClient.Builder()
 *     .addInterceptor(AuthInterceptor())
 *     .addInterceptor(HttpMonitorInterceptor())
 *     .build()
 * ```
 */
class HttpMonitorInterceptor @JvmOverloads constructor(
    private val maxBodyBytes: Long = -1L
) : Interceptor {

    private val bodyLimit: Long
        get() = if (maxBodyBytes >= 0) maxBodyBytes else HttpMonitor.maxBodyBytes

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!HttpMonitor.isEnabled) return chain.proceed(request)

        val id = HttpMonitor.nextId()
        val captured = captureRequest(request)

        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            HttpMonitor.record(id, HttpSource.OKHTTP, captured, null, error.toString())
            throw error
        }

        HttpMonitor.record(id, HttpSource.OKHTTP, captured, captureResponse(response))
        return response
    }

    private fun captureRequest(request: Request): HttpRequest {
        val headers = Headers()
        request.headers.forEach { (name, value) -> headers.add(name, value) }

        val captured = HttpRequest(
            method = request.method,
            url = request.url.toString(),
            headers = headers
        )

        val body = request.body ?: return captured
        if (!headers.contains("Content-Type")) {
            body.contentType()?.let { headers["Content-Type"] = it.toString() }
        }
        // A duplex or one-shot body can only be written once, and that write belongs to the network.
        if (body.isDuplex() || body.isOneShot()) {
            captured.bodySize = runCatching { body.contentLength() }.getOrDefault(-1L).coerceAtLeast(0)
            return captured
        }

        runCatching {
            Buffer().use { buffer ->
                body.writeTo(buffer)
                captured.bodySize = buffer.size
                val limit = minOf(buffer.size, bodyLimit)
                captured.body = buffer.readByteArray(limit)
                captured.bodyTruncated = buffer.size > limit
            }
        }
        return captured
    }

    private fun captureResponse(response: Response): HttpResponse {
        val headers = Headers()
        response.headers.forEach { (name, value) -> headers.add(name, value) }

        val captured = HttpResponse(
            statusCode = response.code,
            statusMessage = response.message,
            headers = headers
        )

        // peekBody leaves the real body untouched for the caller.
        runCatching { response.peekBody(bodyLimit) }.getOrNull()?.let { peeked ->
            val bytes = peeked.bytes()
            captured.body = bytes
            val declared = response.body?.contentLength() ?: -1L
            captured.bodySize = if (declared >= 0) declared else bytes.size.toLong()
            captured.bodyTruncated = bytes.size.toLong() >= bodyLimit && captured.bodySize > bytes.size
        }
        return captured
    }
}
