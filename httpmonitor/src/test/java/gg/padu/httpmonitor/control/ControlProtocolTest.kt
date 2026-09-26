package gg.padu.httpmonitor.control

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProtocolTest {

    @Test
    fun `control base sits one path over from ingest`() {
        assertEquals(
            "https://host/api/v1/control",
            ControlProtocol.controlBase("https://host/api/v1/ingest")
        )
    }

    @Test
    fun `a trailing slash on the ingest endpoint is tolerated`() {
        assertEquals(
            "https://host/api/v1/control",
            ControlProtocol.controlBase("https://host/api/v1/ingest/")
        )
    }

    @Test
    fun `an endpoint that is already the api base is taken as-is`() {
        assertEquals(
            "https://host/api/v1/control",
            ControlProtocol.controlBase("https://host/api/v1")
        )
    }

    @Test
    fun `the commands url carries the install id, encoded`() {
        val url = ControlProtocol.commandsUrl("https://host/api/v1/control", "install/one two")

        assertTrue(url, url.startsWith("https://host/api/v1/control/commands?device_uid="))
        // A uid with a slash or space must not break the query.
        assertTrue(url, url.endsWith("install%2Fone+two") || url.endsWith("install%2Fone%20two"))
    }

    @Test
    fun `capture and fail urls hang off the command`() {
        assertEquals(
            "https://host/api/v1/control/commands/42/capture",
            ControlProtocol.captureUrl("https://host/api/v1/control", 42)
        )
        assertEquals(
            "https://host/api/v1/control/commands/42/fail",
            ControlProtocol.failUrl("https://host/api/v1/control", 42)
        )
    }

    @Test
    fun `commands are parsed with their type and params`() {
        val commands = ControlProtocol.parseCommands(
            """{"commands":[{"id":7,"type":"screenshot","params":{"scale":2}}]}"""
        )

        assertEquals(1, commands.size)
        assertEquals(7L, commands[0].id)
        assertEquals("screenshot", commands[0].type)
        assertEquals(2, commands[0].params.optInt("scale"))
    }

    @Test
    fun `a command missing its id or type is skipped, not guessed`() {
        val commands = ControlProtocol.parseCommands(
            """{"commands":[{"type":"screenshot"},{"id":9},{"id":10,"type":"screenshot"}]}"""
        )

        assertEquals(1, commands.size)
        assertEquals(10L, commands[0].id)
    }

    @Test
    fun `a command with no params still parses, with empty params`() {
        val commands = ControlProtocol.parseCommands("""{"commands":[{"id":1,"type":"screenshot"}]}""")

        assertEquals(1, commands.size)
        assertEquals(0, commands[0].params.length())
    }

    @Test
    fun `malformed or empty responses yield no commands`() {
        assertTrue(ControlProtocol.parseCommands("not json").isEmpty())
        assertTrue(ControlProtocol.parseCommands("{}").isEmpty())
        assertTrue(ControlProtocol.parseCommands("""{"commands":[]}""").isEmpty())
    }

    @Test
    fun `a tap is read as a fraction of the screenshot`() {
        val tap = ControlProtocol.parseTap(JSONObject("""{"x":0.25,"y":0.8}"""))

        assertEquals(0.25f, tap!!.x, 0.0001f)
        assertEquals(0.8f, tap.y, 0.0001f)
        assertEquals(ControlProtocol.DEFAULT_TAP_HOLD_MS, tap.holdMs)
    }

    @Test
    fun `the edges of the screenshot are tappable`() {
        assertEquals(0f, ControlProtocol.parseTap(JSONObject("""{"x":0,"y":0}"""))!!.x, 0f)
        assertEquals(1f, ControlProtocol.parseTap(JSONObject("""{"x":1,"y":1}"""))!!.y, 0f)
    }

    @Test
    fun `a point off the screenshot is refused, not clamped`() {
        assertNull(ControlProtocol.parseTap(JSONObject("""{"x":1.2,"y":0.5}""")))
        assertNull(ControlProtocol.parseTap(JSONObject("""{"x":0.5,"y":-0.1}""")))
    }

    @Test
    fun `a tap without both coordinates is refused`() {
        assertNull(ControlProtocol.parseTap(JSONObject("""{"x":0.5}""")))
        assertNull(ControlProtocol.parseTap(JSONObject("{}")))
        assertNull(ControlProtocol.parseTap(JSONObject("""{"x":"half","y":0.5}""")))
    }

    @Test
    fun `a hold is honoured, and capped rather than refused`() {
        assertEquals(600L, ControlProtocol.parseTap(JSONObject("""{"x":0.5,"y":0.5,"hold_ms":600}"""))!!.holdMs)
        assertEquals(
            ControlProtocol.MAX_TAP_HOLD_MS,
            ControlProtocol.parseTap(JSONObject("""{"x":0.5,"y":0.5,"hold_ms":600000}"""))!!.holdMs
        )
        assertEquals(0L, ControlProtocol.parseTap(JSONObject("""{"x":0.5,"y":0.5,"hold_ms":-5}"""))!!.holdMs)
    }

    @Test
    fun `a tap command parses alongside a screenshot`() {
        val commands = ControlProtocol.parseCommands(
            """{"commands":[{"id":3,"type":"tap","params":{"x":0.5,"y":0.5}},{"id":4,"type":"screenshot"}]}"""
        )

        assertEquals(2, commands.size)
        assertEquals(ControlProtocol.TYPE_TAP, commands[0].type)
        assertEquals(ControlProtocol.TYPE_SCREENSHOT, commands[1].type)
    }

    @Test
    fun `named keys map to the codes the app can press`() {
        assertEquals(KeyEvent.KEYCODE_BACK, ControlProtocol.parseKey(JSONObject("""{"key":"back"}""")))
        assertEquals(KeyEvent.KEYCODE_ENTER, ControlProtocol.parseKey(JSONObject("""{"key":"enter"}""")))
        assertEquals(KeyEvent.KEYCODE_DEL, ControlProtocol.parseKey(JSONObject("""{"key":"del"}""")))
        // Case and stray spacing are the dashboard's business, not a refusal.
        assertEquals(KeyEvent.KEYCODE_BACK, ControlProtocol.parseKey(JSONObject("""{"key":" BACK "}""")))
    }

    @Test
    fun `a key the app cannot press is refused`() {
        // Home and Recents belong to the system; no dispatch inside the app reaches them.
        assertNull(ControlProtocol.parseKey(JSONObject("""{"key":"home"}""")))
        assertNull(ControlProtocol.parseKey(JSONObject("""{"key":""}""")))
        assertNull(ControlProtocol.parseKey(JSONObject("{}")))
    }

    @Test
    fun `text to type is read as given`() {
        assertEquals("Hello, world", ControlProtocol.parseText(JSONObject("""{"text":"Hello, world"}""")))
    }

    @Test
    fun `empty text is refused rather than costing a round trip`() {
        assertNull(ControlProtocol.parseText(JSONObject("""{"text":""}""")))
        assertNull(ControlProtocol.parseText(JSONObject("{}")))
    }

    @Test
    fun `text past the limit is refused, not truncated`() {
        val long = "a".repeat(ControlProtocol.MAX_TEXT_LENGTH + 1)

        assertNull(ControlProtocol.parseText(JSONObject().put("text", long)))
        assertEquals(
            ControlProtocol.MAX_TEXT_LENGTH,
            ControlProtocol.parseText(JSONObject().put("text", "a".repeat(ControlProtocol.MAX_TEXT_LENGTH)))?.length
        )
    }

    @Test
    fun `key and text commands parse like any other`() {
        val commands = ControlProtocol.parseCommands(
            """{"commands":[{"id":5,"type":"key","params":{"key":"back"}},{"id":6,"type":"text","params":{"text":"hi"}}]}"""
        )

        assertEquals(ControlProtocol.TYPE_KEY, commands[0].type)
        assertEquals("back", commands[0].params.optString("key"))
        assertEquals(ControlProtocol.TYPE_TEXT, commands[1].type)
        assertEquals("hi", commands[1].params.optString("text"))
    }
}
