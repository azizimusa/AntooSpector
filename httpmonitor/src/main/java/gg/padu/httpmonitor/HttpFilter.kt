package gg.padu.httpmonitor

/**
 * Adjusts or drops captured traffic before it reaches the monitor store.
 *
 * Return `null` from either method to skip the whole transaction, for example to keep
 * an auth endpoint out of the log entirely.
 */
interface HttpFilter {
    fun filter(request: HttpRequest): HttpRequest? = request
    fun filter(response: HttpResponse): HttpResponse? = response
}

/** Removes the given header names from every request and response. */
class HeaderRedactingFilter(vararg headerNames: String) : HttpFilter {

    private val names = headerNames.map { it.lowercase() }

    override fun filter(request: HttpRequest): HttpRequest = request.also { redact(it.headers) }

    override fun filter(response: HttpResponse): HttpResponse = response.also { redact(it.headers) }

    private fun redact(headers: Headers) {
        names.forEach { name -> if (headers.contains(name)) headers[name] = REDACTED }
    }

    companion object {
        const val REDACTED = "<redacted>"
    }
}
