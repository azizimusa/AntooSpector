package gg.padu.httpmonitor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        HttpMonitor.clear()
        HttpMonitor.stop()
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
