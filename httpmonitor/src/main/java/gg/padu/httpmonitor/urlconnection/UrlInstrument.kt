package gg.padu.httpmonitor.urlconnection

import gg.padu.httpmonitor.HttpMonitor
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection

/**
 * Drop-in replacement for [URL.openConnection] that makes plain `HttpURLConnection` traffic
 * visible to the monitor:
 *
 * ```
 * val connection = UrlInstrument.openConnection(URL("https://example.com")) as HttpURLConnection
 * ```
 *
 * Call `disconnect()` when done — together with the end of the response stream it is what marks
 * the exchange complete.
 */
object UrlInstrument {

    @JvmStatic
    @JvmOverloads
    fun openConnection(url: URL, proxy: Proxy? = null): URLConnection {
        val connection = if (proxy != null) url.openConnection(proxy) else url.openConnection()
        return instrument(connection)
    }

    /** Wraps an already-opened connection. Non-HTTP connections are returned untouched. */
    @JvmStatic
    fun instrument(connection: URLConnection): URLConnection = when {
        !HttpMonitor.isEnabled -> connection
        connection is MonitoredHttpURLConnection || connection is MonitoredHttpsURLConnection -> connection
        connection is HttpsURLConnection -> MonitoredHttpsURLConnection(connection)
        connection is HttpURLConnection -> MonitoredHttpURLConnection(connection)
        else -> connection
    }
}
