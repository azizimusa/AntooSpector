package gg.padu.httpmonitor.report

import gg.padu.httpmonitor.Headers
import gg.padu.httpmonitor.HttpRequest
import gg.padu.httpmonitor.HttpResponse
import gg.padu.httpmonitor.HttpSource
import gg.padu.httpmonitor.HttpTransaction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionJsonTest {

    private fun transaction(
        request: HttpRequest = HttpRequest(
            method = "POST",
            url = "https://api.example.com/v1/orders?ref=abc",
            headers = Headers().add("Content-Type", "application/json"),
            body = """{"items":2}""".toByteArray(),
            bodySize = 11,
            startedAt = 1_790_000_000_000
        ),
        response: HttpResponse? = HttpResponse(
            statusCode = 201,
            statusMessage = "Created",
            headers = Headers().add("Content-Type", "application/json"),
            body = """{"id":7}""".toByteArray(),
            bodySize = 8
        ).apply { receivedAt = 1_790_000_000_420 },
        error: String? = null
    ) = HttpTransaction(7, HttpSource.OKHTTP, request, response, error)

    private fun encode(transaction: HttpTransaction, maxBodyChars: Int = 16_384): JSONObject =
        TransactionJson.encode(transaction, clientId = 99, maxBodyChars = maxBodyChars)

    @Test
    fun `encodes the shape the dashboard ingests`() {
        val json = encode(transaction())

        assertEquals(99L, json.getLong("id"))
        assertEquals("okhttp", json.getString("source"))
        assertEquals(420L, json.getLong("duration_ms"))
        assertFalse(json.has("error"))

        val request = json.getJSONObject("request")
        assertEquals("POST", request.getString("method"))
        assertEquals("https://api.example.com/v1/orders?ref=abc", request.getString("url"))
        assertEquals(11L, request.getLong("body_size"))
        assertEquals(1_790_000_000_000, request.getLong("started_at"))
        assertEquals("""{"items":2}""", request.getString("body"))

        val response = json.getJSONObject("response")
        assertEquals(201, response.getInt("status_code"))
        assertEquals("Created", response.getString("status_message"))
        assertEquals(1_790_000_000_420, response.getLong("received_at"))
    }

    @Test
    fun `headers are sent as name to list of values`() {
        val headers = Headers()
            .add("Accept", "application/json")
            .add("Accept", "text/plain")

        val json = encode(transaction(request = HttpRequest("GET", "https://a.test/", headers)))
        val accept = json.getJSONObject("request").getJSONObject("headers").getJSONArray("Accept")

        assertEquals(2, accept.length())
        assertEquals("application/json", accept.getString(0))
        assertEquals("text/plain", accept.getString(1))
    }

    @Test
    fun `a failure carries its error and no response`() {
        val json = encode(
            transaction(response = null, error = "java.net.SocketTimeoutException: timeout")
        )

        assertEquals("java.net.SocketTimeoutException: timeout", json.getString("error"))
        assertFalse(json.has("response"))
    }

    @Test
    fun `a binary body is described rather than shipped`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)
        val json = encode(
            transaction(
                request = HttpRequest(
                    method = "POST",
                    url = "https://a.test/upload",
                    headers = Headers().add("Content-Type", "image/png"),
                    body = png,
                    bodySize = png.size.toLong()
                )
            )
        )

        assertTrue(json.getJSONObject("request").getString("body").startsWith("<binary body,"))
    }

    @Test
    fun `an oversized body is truncated with a marker`() {
        val long = "x".repeat(500)
        val json = encode(
            transaction(
                request = HttpRequest(
                    method = "POST",
                    url = "https://a.test/",
                    headers = Headers().add("Content-Type", "text/plain"),
                    body = long.toByteArray(),
                    bodySize = 500
                )
            ),
            maxBodyChars = 100
        )

        val body = json.getJSONObject("request").getString("body")
        assertTrue(body.startsWith("x".repeat(100)))
        assertTrue(body.endsWith("… truncated by the reporter …"))
    }

    @Test
    fun `an empty body is left out entirely`() {
        val json = encode(transaction(request = HttpRequest("GET", "https://a.test/")))

        assertFalse(json.getJSONObject("request").has("body"))
    }

    @Test
    fun `a batch carries the device alongside its transactions`() {
        val device = DeviceInfo(uid = "install-1", model = "Pixel 8", osVersion = "Android 14")
        val batch = TransactionJson.batch(device, listOf(encode(transaction()), encode(transaction())))

        assertEquals("install-1", batch.getJSONObject("device").getString("uid"))
        assertEquals("Pixel 8", batch.getJSONObject("device").getString("model"))
        assertEquals(2, batch.getJSONArray("transactions").length())
    }

    @Test
    fun `a batch names the app it came from, so one key can cover several`() {
        val device = DeviceInfo(
            uid = "install-1",
            appVersion = "1.4.0",
            appBuild = "42",
            packageName = "gg.padu.ke",
            appLabel = "Paduke"
        )

        val app = TransactionJson.batch(device, listOf(encode(transaction()))).getJSONObject("app")

        assertEquals("android", app.getString("platform"))
        assertEquals("gg.padu.ke", app.getString("package_name"))
        assertEquals("Paduke", app.getString("label"))
        assertEquals("1.4.0", app.getString("version"))
        assertEquals("42", app.getString("build"))
        // Nothing was set to tell two builds of one package apart.
        assertFalse(app.has("tag"))
    }

    @Test
    fun `a tag tells two builds of the same package apart`() {
        val device = DeviceInfo(uid = "install-1", packageName = "gg.padu.ke", tag = "staging")

        val app = TransactionJson.batch(device, listOf(encode(transaction()))).getJSONObject("app")

        assertEquals("staging", app.getString("tag"))
        assertEquals("gg.padu.ke", app.getString("package_name"))
    }

    @Test
    fun `the device object stays about the device`() {
        val device = DeviceInfo(uid = "install-1", packageName = "gg.padu.ke", tag = "staging")

        val json = device.toJson()

        // Package and tag belong to the app object; repeating them here would
        // invite the dashboard to read the app's identity off the wrong one.
        assertFalse(json.has("package_name"))
        assertFalse(json.has("tag"))
    }

    @Test
    fun `client ids from two sessions never collide`() {
        val first = TransactionJson.sessionBase(1_790_000_000_000)
        val second = TransactionJson.sessionBase(1_790_000_001_000)

        // Both sessions hand out the same raw ids, starting at 1.
        val fromFirst = (1L..500L).map { TransactionJson.clientId(first, it) }
        val fromSecond = (1L..500L).map { TransactionJson.clientId(second, it) }

        assertNotEquals(first, second)
        assertTrue((fromFirst intersect fromSecond.toSet()).isEmpty())
        assertEquals(1000, (fromFirst + fromSecond).distinct().size)
    }

    @Test
    fun `client ids stay inside their own session block`() {
        val base = TransactionJson.sessionBase(1_790_000_000_000)

        assertEquals(base + 1, TransactionJson.clientId(base, 1))
        assertEquals(
            base,
            TransactionJson.clientId(base, TransactionJson.IDS_PER_SESSION)
        )
    }
}
