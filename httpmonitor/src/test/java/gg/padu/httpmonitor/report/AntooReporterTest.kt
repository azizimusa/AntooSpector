package gg.padu.httpmonitor.report

import gg.padu.httpmonitor.Headers
import gg.padu.httpmonitor.HttpRequest
import gg.padu.httpmonitor.HttpResponse
import gg.padu.httpmonitor.HttpSource
import gg.padu.httpmonitor.HttpTransaction
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AntooReporterTest {

    private lateinit var server: MockWebServer
    private lateinit var endpoint: String
    private var reporter: AntooReporter? = null

    private val device = DeviceInfo(uid = "install-1", model = "Pixel 8")

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        endpoint = server.url("/api/v1/ingest").toString()
    }

    @After
    fun tearDown() {
        reporter?.close()
        server.shutdown()
    }

    private fun reporter(
        batchSize: Int = 50,
        flushIntervalMs: Long = 60_000L,
        queueCapacity: Int = 500,
        // Off unless a case is about them, so a quiet reporter stays quiet here.
        heartbeatIntervalMs: Long = AntooReporter.HEARTBEAT_OFF
    ) = AntooReporter(
        endpoint = endpoint,
        apiKey = "antoo_test_key",
        device = device,
        batchSize = batchSize,
        flushIntervalMs = flushIntervalMs,
        queueCapacity = queueCapacity,
        heartbeatIntervalMs = heartbeatIntervalMs
    ).also { reporter = it }

    private fun transaction(id: Long, url: String = "https://api.example.com/v1/feed") =
        HttpTransaction(
            id = id,
            source = HttpSource.OKHTTP,
            request = HttpRequest("GET", url, Headers().add("Accept", "application/json")),
            response = HttpResponse(200, "OK")
        )

    private fun accepted() = MockResponse().setResponseCode(202).setBody("""{"accepted":1}""")

    /**
     * Answers everything with 202. A reporter that heartbeats on a tick of its own
     * sends more than a queue of responses can be sized for.
     */
    private fun alwaysAccepts() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(202)
        }
    }

    private fun take(timeoutMs: Long = 5_000): RecordedRequest? =
        server.takeRequest(timeoutMs, TimeUnit.MILLISECONDS)

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    @Test
    fun `flush posts the queue with the ingest key`() {
        server.enqueue(accepted())

        val reporter = reporter()
        reporter.report(transaction(1))
        reporter.flush()

        val request = take()
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v1/ingest", request.path)
        assertEquals("antoo_test_key", request.getHeader("X-Antoo-Key"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))

        val body = request.json()
        assertEquals("install-1", body.getJSONObject("device").getString("uid"))
        assertEquals(1, body.getJSONArray("transactions").length())
    }

    @Test
    fun `a full batch is sent without waiting for the interval`() {
        server.enqueue(accepted())

        // The interval here is a minute; only reaching batchSize can send this.
        val reporter = reporter(batchSize = 3)
        repeat(3) { reporter.report(transaction(it + 1L)) }

        val request = take()
        assertNotNull(request)
        assertEquals(3, request!!.json().getJSONArray("transactions").length())
    }

    @Test
    fun `more than one batch is drained in order`() {
        repeat(2) { server.enqueue(accepted()) }

        val reporter = reporter(batchSize = 2)
        repeat(4) { reporter.report(transaction(it + 1L)) }
        reporter.flush()

        val first = take()!!.json().getJSONArray("transactions")
        val second = take()!!.json().getJSONArray("transactions")

        assertEquals(2, first.length())
        assertEquals(2, second.length())
        assertTrue(first.getJSONObject(0).getLong("id") < second.getJSONObject(0).getLong("id"))
    }

    @Test
    fun `a server error is retried with the same transactions`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(accepted())

        val reporter = reporter(batchSize = 1, flushIntervalMs = 100L)
        reporter.report(transaction(1))

        val failed = take()
        val retried = take()

        assertNotNull(failed)
        assertNotNull(retried)
        assertEquals(
            failed!!.json().getJSONArray("transactions").getJSONObject(0).getLong("id"),
            retried!!.json().getJSONArray("transactions").getJSONObject(0).getLong("id")
        )
    }

    @Test
    fun `a rejected key is not retried forever`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val reporter = reporter(batchSize = 1, flushIntervalMs = 100L)
        reporter.report(transaction(1))

        assertNotNull(take())
        // The batch is dropped rather than wedging the queue behind it.
        assertNull(take(timeoutMs = 700))
    }

    @Test
    fun `the reporter never uploads its own uploads`() {
        val reporter = reporter(batchSize = 1)
        reporter.report(transaction(1, url = endpoint))

        assertNull(take(timeoutMs = 500))
    }

    @Test
    fun `a queue that outruns the network drops its oldest entries`() {
        server.enqueue(accepted())

        val reporter = reporter(batchSize = 100, queueCapacity = 3)
        (1L..10L).forEach { reporter.report(transaction(it)) }
        reporter.flush()

        val sent = take()!!.json().getJSONArray("transactions")
        val ids = (0 until sent.length()).map { sent.getJSONObject(it).getLong("id") }

        assertEquals(3, ids.size)
        // The three newest survived; the oldest seven were dropped.
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.last() - 2, ids.first())
    }

    @Test
    fun `closing sends what is still queued`() {
        server.enqueue(accepted())

        val reporter = reporter()
        reporter.report(transaction(1))
        reporter.close()

        assertNotNull(take())
    }

    @Test
    fun `reporting after close is ignored`() {
        val reporter = reporter()
        reporter.close()
        take(timeoutMs = 300)

        reporter.report(transaction(1))
        reporter.flush()

        assertNull(take(timeoutMs = 500))
    }

    @Test
    fun `an idle reporter says it is still here`() {
        alwaysAccepts()
        val reporter = reporter(heartbeatIntervalMs = 50L)

        // Nothing captured, so the only thing to send is the fact of being alive.
        reporter.flush()

        val beat = take()
        assertNotNull(beat)
        assertEquals("POST", beat!!.method)
        assertEquals("antoo_test_key", beat.getHeader("X-Antoo-Key"))

        val json = beat.json()
        assertEquals(0, json.getJSONArray("transactions").length())
        assertEquals("install-1", json.getJSONObject("device").getString("uid"))
        // Short enough to be worth sending every interval.
        assertTrue("heartbeat was ${beat.bodySize} bytes", beat.bodySize < 300)
    }

    @Test
    fun `heartbeats can be turned off`() {
        val reporter = reporter(heartbeatIntervalMs = AntooReporter.HEARTBEAT_OFF)

        reporter.flush()

        assertNull(take(timeoutMs = 500))
    }

    @Test
    fun `presence is announced at startup rather than an interval later`() {
        alwaysAccepts()

        // A minute between flushes, five seconds between heartbeats: waiting for
        // either would fail this. The reporter reports in as it starts.
        reporter(heartbeatIntervalMs = 5_000L)

        val hello = take(timeoutMs = 2_000)
        assertNotNull("nothing was sent at startup", hello)
        assertEquals(0, hello!!.json().getJSONArray("transactions").length())
    }

    @Test
    fun `heartbeats keep their own cadence between flushes`() {
        alwaysAccepts()

        reporter(heartbeatIntervalMs = 100L)

        // Three in a row, none of them waiting on the one-minute flush interval.
        repeat(3) { assertNotNull("heartbeat ${it + 1} never came", take(timeoutMs = 2_000)) }
    }

    @Test
    fun `a heartbeat says how often it will be heard from`() {
        alwaysAccepts()

        reporter(heartbeatIntervalMs = 5_000L)

        val device = take(timeoutMs = 2_000)!!.json().getJSONObject("device")
        assertEquals(5_000L, device.getLong("report_interval_ms"))
    }

    @Test
    fun `queued traffic still waits for the flush interval`() {
        alwaysAccepts()

        // Heartbeats tick every 50 ms, but a flush is a minute away: the ticks
        // must not carry the queue out early.
        val reporter = reporter(heartbeatIntervalMs = 50L)
        reporter.report(transaction(1))

        // The heartbeats themselves are the only traffic for the next while.
        repeat(3) {
            val request = take(timeoutMs = 2_000)
            assertEquals(0, request!!.json().getJSONArray("transactions").length())
        }
    }

    @Test
    fun `a batch counts as saying it is still here`() {
        server.enqueue(accepted())
        val reporter = reporter(heartbeatIntervalMs = 60_000L)

        reporter.report(transaction(1))
        reporter.flush()

        val batch = take()
        assertEquals(1, batch!!.json().getJSONArray("transactions").length())

        // The batch is contact enough; no heartbeat chases it.
        reporter.flush()
        assertNull(take(timeoutMs = 500))
    }
}
