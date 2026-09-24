package gg.padu.httpmonitor.urlconnection

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.Permission
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory

/** HTTPS counterpart of [MonitoredHttpURLConnection]. */
internal class MonitoredHttpsURLConnection(
    private val delegate: HttpsURLConnection
) : HttpsURLConnection(delegate.url) {

    private val recorder = UrlConnectionRecorder(delegate)

    override fun getCipherSuite(): String = delegate.cipherSuite

    override fun getLocalCertificates(): Array<Certificate>? = delegate.localCertificates

    @Throws(SSLPeerUnverifiedException::class)
    override fun getServerCertificates(): Array<Certificate> = delegate.serverCertificates

    @Throws(SSLPeerUnverifiedException::class)
    override fun getPeerPrincipal(): Principal = delegate.peerPrincipal

    override fun getLocalPrincipal(): Principal? = delegate.localPrincipal

    override fun setHostnameVerifier(verifier: HostnameVerifier) {
        delegate.hostnameVerifier = verifier
    }

    override fun getHostnameVerifier(): HostnameVerifier = delegate.hostnameVerifier

    override fun setSSLSocketFactory(factory: SSLSocketFactory) {
        delegate.sslSocketFactory = factory
    }

    override fun getSSLSocketFactory(): SSLSocketFactory = delegate.sslSocketFactory

    override fun connect() {
        runCatching { delegate.connect() }.onFailure { recorder.onFailure(it) }.getOrThrow()
    }

    override fun disconnect() {
        recorder.finish()
        delegate.disconnect()
    }

    override fun usingProxy(): Boolean = delegate.usingProxy()

    override fun getOutputStream(): OutputStream = try {
        recorder.wrapOutput(delegate.outputStream)
    } catch (error: IOException) {
        recorder.onFailure(error)
        throw error
    }

    override fun getInputStream(): InputStream = try {
        recorder.onResponse(delegate.responseCode, delegate.responseMessage)
        recorder.wrapInput(delegate.inputStream)!!
    } catch (error: IOException) {
        recorder.onFailure(error)
        throw error
    }

    override fun getErrorStream(): InputStream? {
        runCatching { recorder.onResponse(delegate.responseCode, delegate.responseMessage) }
        return recorder.wrapInput(delegate.errorStream)
    }

    override fun getResponseCode(): Int = try {
        delegate.responseCode.also { recorder.onResponse(it, delegate.responseMessage) }
    } catch (error: IOException) {
        recorder.onFailure(error)
        throw error
    }

    override fun getResponseMessage(): String? = delegate.responseMessage

    override fun setRequestMethod(method: String) {
        delegate.requestMethod = method
    }

    override fun getRequestMethod(): String = delegate.requestMethod

    override fun setRequestProperty(key: String, value: String?) = delegate.setRequestProperty(key, value)

    override fun addRequestProperty(key: String, value: String?) = delegate.addRequestProperty(key, value)

    override fun getRequestProperty(key: String): String? = delegate.getRequestProperty(key)

    override fun getRequestProperties(): MutableMap<String, MutableList<String>> = delegate.requestProperties

    override fun getHeaderField(name: String): String? = delegate.getHeaderField(name)

    override fun getHeaderField(index: Int): String? = delegate.getHeaderField(index)

    override fun getHeaderFieldKey(index: Int): String? = delegate.getHeaderFieldKey(index)

    override fun getHeaderFields(): MutableMap<String, MutableList<String>> = delegate.headerFields

    override fun getHeaderFieldInt(name: String, default: Int): Int = delegate.getHeaderFieldInt(name, default)

    override fun getHeaderFieldLong(name: String, default: Long): Long = delegate.getHeaderFieldLong(name, default)

    override fun getHeaderFieldDate(name: String, default: Long): Long = delegate.getHeaderFieldDate(name, default)

    override fun setDoInput(value: Boolean) {
        delegate.doInput = value
    }

    override fun getDoInput(): Boolean = delegate.doInput

    override fun setDoOutput(value: Boolean) {
        delegate.doOutput = value
    }

    override fun getDoOutput(): Boolean = delegate.doOutput

    override fun setUseCaches(value: Boolean) {
        delegate.useCaches = value
    }

    override fun getUseCaches(): Boolean = delegate.useCaches

    override fun setAllowUserInteraction(value: Boolean) {
        delegate.allowUserInteraction = value
    }

    override fun getAllowUserInteraction(): Boolean = delegate.allowUserInteraction

    override fun setDefaultUseCaches(value: Boolean) {
        delegate.defaultUseCaches = value
    }

    override fun getDefaultUseCaches(): Boolean = delegate.defaultUseCaches

    override fun setIfModifiedSince(value: Long) {
        delegate.ifModifiedSince = value
    }

    override fun getIfModifiedSince(): Long = delegate.ifModifiedSince

    override fun setConnectTimeout(timeout: Int) {
        delegate.connectTimeout = timeout
    }

    override fun getConnectTimeout(): Int = delegate.connectTimeout

    override fun setReadTimeout(timeout: Int) {
        delegate.readTimeout = timeout
    }

    override fun getReadTimeout(): Int = delegate.readTimeout

    override fun setInstanceFollowRedirects(value: Boolean) {
        delegate.instanceFollowRedirects = value
    }

    override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects

    override fun setChunkedStreamingMode(chunkLength: Int) = delegate.setChunkedStreamingMode(chunkLength)

    override fun setFixedLengthStreamingMode(contentLength: Int) =
        delegate.setFixedLengthStreamingMode(contentLength)

    override fun setFixedLengthStreamingMode(contentLength: Long) =
        delegate.setFixedLengthStreamingMode(contentLength)

    override fun getContent(): Any? = delegate.content

    override fun getContent(classes: Array<out Class<*>>): Any? = delegate.getContent(classes)

    override fun getContentType(): String? = delegate.contentType

    override fun getContentEncoding(): String? = delegate.contentEncoding

    override fun getContentLength(): Int = delegate.contentLength

    override fun getContentLengthLong(): Long = delegate.contentLengthLong

    override fun getDate(): Long = delegate.date

    override fun getExpiration(): Long = delegate.expiration

    override fun getLastModified(): Long = delegate.lastModified

    override fun getPermission(): Permission = delegate.permission

    override fun getURL(): URL = delegate.url

    override fun toString(): String = delegate.toString()
}
