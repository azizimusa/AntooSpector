package gg.padu.httpmonitor

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Locale

/** Decoding and pretty-printing of captured payloads, used by the viewer. */
object Bodies {

    private val textualTypes = listOf("text/", "json", "xml", "x-www-form-urlencoded", "javascript", "html")

    fun isTextual(contentType: String?): Boolean {
        val type = contentType?.lowercase(Locale.US) ?: return true
        return textualTypes.any { type.contains(it) }
    }

    fun charsetOf(contentType: String?): Charset {
        val name = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
            ?: return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    /** Human-readable payload, pretty-printed for JSON and XML. Never null, may be empty. */
    fun asText(body: ByteArray?, contentType: String?, truncated: Boolean = false): String {
        if (body == null || body.isEmpty()) return ""
        if (!isTextual(contentType) && looksBinary(body)) {
            return "<binary body, ${formatSize(body.size.toLong())}>"
        }
        val text = String(body, charsetOf(contentType))
        val pretty = prettify(text, contentType)
        return if (truncated) pretty + "\n\n… truncated" else pretty
    }

    fun prettify(text: String, contentType: String?): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        val type = contentType?.lowercase(Locale.US).orEmpty()
        return when {
            type.contains("json") || trimmed.startsWith('{') || trimmed.startsWith('[') ->
                prettyJson(trimmed) ?: text
            type.contains("xml") || trimmed.startsWith('<') -> prettyXml(trimmed)
            else -> text
        }
    }

    private fun prettyJson(text: String): String? = runCatching {
        when (text.first()) {
            '{' -> JSONObject(text).toString(2)
            '[' -> JSONArray(text).toString(2)
            else -> null
        }
    }.getOrNull()

    private fun prettyXml(text: String): String {
        val compact = text.replace(">\\s+<".toRegex(), "><")
        val builder = StringBuilder()
        var pendingText: String? = null
        var indent = 0
        var cursor = 0
        while (cursor < compact.length) {
            val open = compact.indexOf('<', cursor)
            if (open < 0) break
            if (open > cursor) {
                compact.substring(cursor, open).trim().takeIf { it.isNotEmpty() }?.let { pendingText = it }
            }
            val close = compact.indexOf('>', open)
            if (close < 0) break
            val tag = compact.substring(open, close + 1)
            val closing = tag.startsWith("</")
            val standalone = tag.endsWith("/>") || tag.startsWith("<?") || tag.startsWith("<!")
            if (closing) indent = (indent - 1).coerceAtLeast(0)
            val inlineText = pendingText
            if (closing && inlineText != null) {
                builder.append(inlineText).append(tag)
            } else {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append("  ".repeat(indent)).append(inlineText.orEmpty()).append(tag)
            }
            pendingText = null
            if (!closing && !standalone) indent++
            cursor = close + 1
        }
        return if (builder.isEmpty()) text else builder.toString()
    }

    private fun looksBinary(body: ByteArray): Boolean {
        val sample = body.take(64)
        return sample.any { it.toInt() == 0 } ||
            sample.count { it.toInt() in 0..8 || it.toInt() in 14..31 } > sample.size / 8
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f kB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Reproduces the request as a cURL command, for replaying it outside the app. */
    fun asCurl(transaction: HttpTransaction): String = buildString {
        append("curl -X ").append(transaction.request.method)
        transaction.request.headers.forEach { (name, value) ->
            append(" \\\n  -H '").append(name).append(": ").append(value.replace("'", "'\\''")).append('\'')
        }
        val body = transaction.request.body
        if (body != null && body.isNotEmpty() && isTextual(transaction.request.contentType)) {
            val text = String(body, charsetOf(transaction.request.contentType)).replace("'", "'\\''")
            append(" \\\n  --data '").append(text).append('\'')
        }
        append(" \\\n  '").append(transaction.request.url).append('\'')
    }
}
