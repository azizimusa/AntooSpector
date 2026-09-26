package gg.padu.httpmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadersTest {

    @Test
    fun `lookup ignores case but keeps the original name`() {
        val headers = Headers().add("Content-Type", "application/json")

        assertEquals("application/json", headers["content-type"])
        assertTrue(headers.contains("CONTENT-TYPE"))
        assertEquals(listOf("Content-Type"), headers.names())
    }

    @Test
    fun `multiple values of one name are kept`() {
        val headers = Headers().add("Set-Cookie", "a=1").add("set-cookie", "b=2")

        assertEquals(listOf("a=1", "b=2"), headers.all("Set-Cookie"))
        assertEquals(2, headers.size)
        assertEquals("a=1", headers["Set-Cookie"])
    }

    @Test
    fun `set replaces every value and remove drops the name`() {
        val headers = Headers().add("Authorization", "Bearer token").add("Authorization", "extra")

        headers["authorization"] = "<redacted>"
        assertEquals(listOf("<redacted>"), headers.all("Authorization"))
        // Rewriting a value must not restyle the name.
        assertEquals(listOf("Authorization"), headers.names())

        headers.remove("Authorization")
        assertFalse(headers.contains("Authorization"))
        assertNull(headers["Authorization"])
    }

    @Test
    fun `set on an unseen name uses the casing it was given`() {
        val headers = Headers()

        headers["X-Trace-Id"] = "abc-123"

        assertEquals(listOf("X-Trace-Id"), headers.names())
        assertEquals("abc-123", headers["x-trace-id"])
    }

    @Test
    fun `null status-line key from HttpURLConnection is skipped`() {
        val headers = Headers(mapOf(null to listOf("HTTP/1.1 200 OK"), "Server" to listOf("nginx")))

        assertEquals(1, headers.size)
        assertEquals("nginx", headers["server"])
    }
}
