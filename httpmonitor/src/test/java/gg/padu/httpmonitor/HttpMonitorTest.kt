package gg.padu.httpmonitor

import gg.padu.httpmonitor.report.TransactionReporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpMonitorTest {

    private val filters = mutableListOf<HttpFilter>()

    @Before
    fun setUp() {
        HttpMonitor.start(maxTransactions = 3)
    }

    @After
    fun tearDown() {
        filters.forEach { HttpMonitor.removeFilter(it) }
        HttpMonitor.report(null)
        HttpMonitor.clear()
        HttpMonitor.stop()
    }

    /** Collects what the monitor hands a reporter, and how often it is flushed. */
    private class RecordingReporter : TransactionReporter {
        val reported = mutableListOf<HttpTransaction>()
        var flushes = 0
        var closed = false

        override fun report(transaction: HttpTransaction) { reported += transaction }
        override fun flush() { flushes++ }
        override fun close() { closed = true }
    }

    private fun install(filter: HttpFilter) = filter.also {
        filters.add(it)
        HttpMonitor.addFilter(it)
    }

    private fun record(url: String = "https://example.com/a", status: Int = 200) = HttpMonitor.record(
        id = HttpMonitor.nextId(),
        source = HttpSource.MANUAL,
        request = HttpRequest("GET", url, Headers().add("Authorization", "Bearer secret")),
        response = HttpResponse(status, "OK")
    )

    @Test
    fun `capture is a no-op while stopped`() {
        HttpMonitor.stop()

        assertNull(record())
        assertEquals(0, HttpMonitor.transactions().size)
    }

    @Test
    fun `store keeps newest transactions within capacity`() {
        repeat(5) { record(url = "https://example.com/$it") }

        val urls = HttpMonitor.transactions().map { it.request.url }
        assertEquals(
            listOf("https://example.com/4", "https://example.com/3", "https://example.com/2"),
            urls
        )
    }

    @Test
    fun `filters can rewrite headers`() {
        install(HeaderRedactingFilter("Authorization"))

        assertNotNull(record())

        val transaction = HttpMonitor.transactions().first()
        assertEquals(HeaderRedactingFilter.REDACTED, transaction.request.headers["Authorization"])
    }

    @Test
    fun `a filter returning null drops the whole transaction`() {
        install(object : HttpFilter {
            override fun filter(request: HttpRequest): HttpRequest? =
                request.takeUnless { it.url.contains("/secret") }
        })

        assertNull(record(url = "https://example.com/secret"))
        assertNotNull(record(url = "https://example.com/public"))
        assertEquals(1, HttpMonitor.transactions().size)
    }

    @Test
    fun `transactions can be looked up by id`() {
        val transaction = record()!!

        assertEquals(transaction.id, HttpMonitor.find(transaction.id)?.id)
        assertNull(HttpMonitor.find(-1))
    }

    @Test
    fun `an installed reporter sees every stored transaction`() {
        val reporter = RecordingReporter()
        HttpMonitor.report(reporter)

        record(url = "https://example.com/a")
        record(url = "https://example.com/b")

        assertEquals(
            listOf("https://example.com/a", "https://example.com/b"),
            reporter.reported.map { it.request.url }
        )
    }

    @Test
    fun `the reporter only sees what the filters left`() {
        val reporter = RecordingReporter()
        HttpMonitor.report(reporter)
        install(HeaderRedactingFilter("Authorization"))
        install(object : HttpFilter {
            override fun filter(request: HttpRequest): HttpRequest? =
                request.takeUnless { it.url.contains("/secret") }
        })

        record(url = "https://example.com/secret")
        record(url = "https://example.com/public")

        assertEquals(1, reporter.reported.size)
        assertEquals(
            HeaderRedactingFilter.REDACTED,
            reporter.reported.single().request.headers["Authorization"]
        )
    }

    @Test
    fun `a throwing reporter never breaks the call being recorded`() {
        HttpMonitor.report(object : TransactionReporter {
            override fun report(transaction: HttpTransaction) = error("reporter is broken")
        })

        assertNotNull(record())
        assertEquals(1, HttpMonitor.transactions().size)
    }

    @Test
    fun `installing a reporter closes the one it replaces`() {
        val first = RecordingReporter()
        val second = RecordingReporter()

        HttpMonitor.report(first)
        HttpMonitor.report(second)

        assertTrue(first.closed)
        assertEquals(false, second.closed)

        record()
        assertEquals(0, first.reported.size)
        assertEquals(1, second.reported.size)
    }

    @Test
    fun `flushReports reaches the installed reporter`() {
        val reporter = RecordingReporter()
        HttpMonitor.report(reporter)

        HttpMonitor.flushReports()

        assertEquals(1, reporter.flushes)
    }

    @Test
    fun `nothing is reported while capture is stopped`() {
        val reporter = RecordingReporter()
        HttpMonitor.report(reporter)
        HttpMonitor.stop()

        record()

        assertEquals(0, reporter.reported.size)
    }

    @Test
    fun `transaction derives host path and duration`() {
        val request = HttpRequest(
            method = "GET",
            url = "https://example.com/v1/items?page=2",
            startedAt = 1_000L
        )
        val response = HttpResponse(200, "OK").apply { receivedAt = 1_250L }
        val transaction = HttpTransaction(1, HttpSource.MANUAL, request, response)

        assertEquals("example.com", transaction.host)
        assertEquals("/v1/items?page=2", transaction.path)
        assertEquals(250L, transaction.durationMs)
    }
}
