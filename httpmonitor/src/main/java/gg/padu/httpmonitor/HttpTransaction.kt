package gg.padu.httpmonitor

/** Where a transaction was captured from. */
enum class HttpSource { OKHTTP, URL_CONNECTION, MANUAL }

/** A captured request. Mutable: [HttpFilter]s may rewrite it before it is stored. */
class HttpRequest(
    var method: String,
    var url: String,
    val headers: Headers = Headers(),
    var body: ByteArray? = null,
    /** Real body size on the wire, which may exceed the retained [body]. */
    var bodySize: Long = 0,
    var bodyTruncated: Boolean = false,
    val startedAt: Long = System.currentTimeMillis()
) {
    val contentType: String? get() = headers["Content-Type"]
}

/** A captured response. Mutable for the same reason as [HttpRequest]. */
class HttpResponse(
    var statusCode: Int,
    var statusMessage: String = "",
    val headers: Headers = Headers(),
    var body: ByteArray? = null,
    var bodySize: Long = 0,
    var bodyTruncated: Boolean = false,
    var receivedAt: Long = System.currentTimeMillis()
) {
    val contentType: String? get() = headers["Content-Type"]
}

/** One completed (or failed) HTTP exchange as shown by the monitor. */
class HttpTransaction(
    val id: Long,
    val source: HttpSource,
    val request: HttpRequest,
    val response: HttpResponse?,
    /** Non-null when the exchange failed before a response was received. */
    val error: String? = null
) {
    val durationMs: Long = ((response?.receivedAt ?: System.currentTimeMillis()) - request.startedAt)
        .coerceAtLeast(0)

    val isFailed: Boolean get() = error != null
    val statusCode: Int get() = response?.statusCode ?: -1

    val host: String? get() = runCatching { java.net.URI(request.url).host }.getOrNull()

    val path: String
        get() = runCatching {
            val uri = java.net.URI(request.url)
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            (uri.rawPath.orEmpty().ifEmpty { "/" }) + query
        }.getOrDefault(request.url)

    /** Total retained payload size, request + response. */
    val totalSize: Long get() = request.bodySize + (response?.bodySize ?: 0)
}
