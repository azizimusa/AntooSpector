package gg.padu.httpmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodiesTest {

    @Test
    fun `json bodies are pretty printed`() {
        val text = Bodies.asText("""{"id":7,"name":"paduke"}""".toByteArray(), "application/json")

        assertTrue(text, text.contains("\"id\": 7"))
        assertTrue(text, text.lines().size > 1)
    }

    @Test
    fun `xml bodies are indented`() {
        val text = Bodies.asText("<root><item>a</item></root>".toByteArray(), "application/xml")

        assertEquals("<root>\n  <item>a</item>\n</root>", text)
    }

    @Test
    fun `truncated bodies are marked`() {
        val text = Bodies.asText("plain".toByteArray(), "text/plain", truncated = true)

        assertTrue(text.endsWith("… truncated"))
    }

    @Test
    fun `binary bodies are summarised instead of mangled`() {
        val body = ByteArray(32) { 0 }

        assertEquals("<binary body, 32 B>", Bodies.asText(body, "image/png"))
    }

    @Test
    fun `charset from content type is honoured`() {
        val body = "héllo".toByteArray(Charsets.ISO_8859_1)

        assertEquals("héllo", Bodies.asText(body, "text/plain; charset=ISO-8859-1"))
    }

    @Test
    fun `sizes are human readable`() {
        assertEquals("512 B", Bodies.formatSize(512))
        assertEquals("1.5 kB", Bodies.formatSize(1536))
        assertEquals("2.0 MB", Bodies.formatSize(2L * 1024 * 1024))
    }

    @Test
    fun `curl command carries method headers and body`() {
        val request = HttpRequest(
            method = "POST",
            url = "https://example.com/v1/items",
            headers = Headers().add("Content-Type", "application/json"),
            body = """{"a":1}""".toByteArray()
        )
        val curl = Bodies.asCurl(HttpTransaction(1, HttpSource.OKHTTP, request, null))

        assertTrue(curl, curl.startsWith("curl -X POST"))
        assertTrue(curl, curl.contains("-H 'Content-Type: application/json'"))
        assertTrue(curl, curl.contains("""--data '{"a":1}'"""))
        assertTrue(curl, curl.trimEnd().endsWith("'https://example.com/v1/items'"))
    }
}
