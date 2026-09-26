package gg.padu.httpmonitor.control

import org.json.JSONObject

/**
 * The wire shape of Device Control, kept apart from the transport so the URLs and
 * the command parsing can be asserted on without a server or a device.
 *
 * The dashboard exposes the control endpoints next to ingest: given the ingest
 * URL a client already reports to, the rest are one path over.
 */
internal object ControlProtocol {

    /** One instruction the dashboard has queued for this install. */
    data class Command(val id: Long, val type: String, val params: JSONObject)

    const val TYPE_SCREENSHOT = "screenshot"

    /**
     * Derives the control base from the ingest endpoint the reporter uses, e.g.
     * `https://host/api/v1/ingest` → `https://host/api/v1/control`. A URL that
     * does not end in `/ingest` is assumed to already be the API base.
     */
    fun controlBase(ingestEndpoint: String): String {
        val trimmed = ingestEndpoint.trim().trimEnd('/')
        val base = if (trimmed.endsWith("/ingest")) trimmed.removeSuffix("/ingest") else trimmed
        return "$base/control"
    }

    fun commandsUrl(controlBase: String, deviceUid: String): String =
        "$controlBase/commands?device_uid=${encode(deviceUid)}"

    fun captureUrl(controlBase: String, commandId: Long): String =
        "$controlBase/commands/$commandId/capture"

    fun failUrl(controlBase: String, commandId: Long): String =
        "$controlBase/commands/$commandId/fail"

    /** Reads the `commands` array of a poll response; anything malformed is skipped. */
    fun parseCommands(body: String): List<Command> {
        val array = runCatching { JSONObject(body).optJSONArray("commands") }.getOrNull()
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id", -1L)
            val type = item.optString("type").orEmpty()
            if (id < 0 || type.isEmpty()) null
            else Command(id, type, item.optJSONObject("params") ?: JSONObject())
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
