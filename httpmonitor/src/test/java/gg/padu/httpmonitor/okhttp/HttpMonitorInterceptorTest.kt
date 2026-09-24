package gg.padu.httpmonitor.okhttp

import gg.padu.httpmonitor.HttpMonitor
import gg.padu.httpmonitor.HttpSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HttpMonitorInterceptorTest {

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().addInterceptor(HttpMonitorInterceptor()).build()

    @Before
    fun setUp() {
        server.start()
        HttpMonitor.start(maxTransactions = 10)
    }

    @After
    fun tearDown() {
        HttpMonitor.clear()
        HttpMonitor.stop()
        HttpMonitor.maxBodyBytes = HttpMonitor.DEFAULT_MAX_BODY_BYTES
        server.shutdown()
    }

    @Test
    fun `request and response are captured`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )

        val request = Request.Builder()
            .url(server.url("/items"))
            .post("""{"name":"paduke"}""".toRequestBody("application/json".toMediaType()))
            .header("X-Trace", "abc")
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val transaction = HttpMonitor.transactions().single()
        assertEquals(HttpSource.OKHTTP, transaction.source)
        assertEquals("POST", transaction.request.method)
        assertEquals("/items", transaction.path)
        assertEquals("abc", transaction.request.headers["X-Trace"])
        assertEquals("""{"name":"paduke"}""", String(transaction.request.body!!))
        assertEquals(201, transaction.statusCode)
        assertEquals("""{"ok":true}""", String(transaction.response!!.body!!))
        assertEquals("application/json", transaction.response!!.headers["Content-Type"])
    }

    @Test
    fun `response body stays readable by the caller`() {
        server.enqueue(MockResponse().setBody("hello"))

        val body = client.newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .use { it.body?.string() }

        assertEquals("hello", body)
        assertEquals("hello", String(HttpMonitor.transactions().single().response!!.body!!))
    }

    @Test
    fun `bodies larger than the limit are truncated but counted`() {
        val payload = "x".repeat(4096)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(payload))
        HttpMonitor.maxBodyBytes = 128

        client.newCall(Request.Builder().url(server.url("/big")).build()).execute().use { it.body?.string() }

        val response = HttpMonitor.transactions().single().response!!
        assertEquals(128, response.body!!.size)
        assertEquals(4096L, response.bodySize)
        assertTrue(response.bodyTruncated)
    }

    @Test
    fun `network failures are recorded and rethrown`() {
        server.shutdown()

        val call = client.newCall(Request.Builder().url(server.url("/gone")).build())
        try {
            call.execute()
        } catch (expected: IOException) {
            // The call is expected to fail; the monitor must still hold the attempt.
        }

        val transaction = HttpMonitor.transactions().single()
        assertTrue(transaction.isFailed)
        assertNotNull(transaction.error)
        assertEquals(null, transaction.response)
    }

    @Test
    fun `capture is skipped while the monitor is stopped`() {
        HttpMonitor.stop()
        server.enqueue(MockResponse().setBody("ignored"))

        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body?.string() }

        assertTrue(HttpMonitor.transactions().isEmpty())
    }
}
