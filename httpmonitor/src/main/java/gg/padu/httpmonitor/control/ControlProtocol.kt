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
    const val TYPE_TAP = "tap"

    /** A spot on the last screenshot, and how long to hold it there. */
    data class Tap(val x: Float, val y: Float, val holdMs: Long)

    /** Long enough to register as a tap and show the app's own press feedback. */
    const val DEFAULT_TAP_HOLD_MS = 80L

    /** Past this a hold is someone's mistake, not an intention. */
    const val MAX_TAP_HOLD_MS = 3_000L

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

    /**
     * Reads where a tap command wants to land. The point is a *fraction* of the
     * captured image (0..1) rather than a pixel, so it survives the downscale the
     * upload applies and a dashboard that knows nothing of the device's
     * resolution. A point outside the image is refused rather than clamped: an
     * out-of-range coordinate means the two sides disagree about something, and
     * guessing at the edge of the screen is a poor way to find out.
     */
    fun parseTap(params: JSONObject): Tap? {
        val x = params.optDouble("x", Double.NaN)
        val y = params.optDouble("y", Double.NaN)
        if (!x.isFinite() || !y.isFinite()) return null
        if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) return null

        val hold = params.optLong("hold_ms", DEFAULT_TAP_HOLD_MS).coerceIn(0L, MAX_TAP_HOLD_MS)

        return Tap(x.toFloat(), y.toFloat(), hold)
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
