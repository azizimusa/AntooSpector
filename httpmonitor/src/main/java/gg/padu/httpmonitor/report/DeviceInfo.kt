package gg.padu.httpmonitor.report

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.UUID

/**
 * Identifies the install a batch came from. [uid] must stay the same across
 * launches — the dashboard groups traffic by it, and uses it to recognise a
 * retried batch.
 */
class DeviceInfo @JvmOverloads constructor(
    val uid: String,
    val label: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val appBuild: String? = null
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        label?.let { put("label", it) }
        manufacturer?.let { put("manufacturer", it) }
        model?.let { put("model", it) }
        osVersion?.let { put("os_version", it) }
        appVersion?.let { put("app_version", it) }
        appBuild?.let { put("app_build", it) }
    }

    companion object {

        private const val PREFS = "gg.padu.httpmonitor"
        private const val KEY_INSTALL_ID = "install_id"

        /**
         * Reads the device and app names, with an install id minted once and kept
         * in the library's own preferences. Uninstalling the app resets it, which
         * is the intent: it identifies an install, not a person.
         */
        @JvmStatic
        fun from(context: Context): DeviceInfo {
            val app = context.applicationContext
            val packageInfo = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0)
            }.getOrNull()

            @Suppress("DEPRECATION")
            val build = packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
            }

            return DeviceInfo(
                uid = installId(app),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                osVersion = "Android ${Build.VERSION.RELEASE}",
                appVersion = packageInfo?.versionName,
                appBuild = build?.toString()
            )
        }

        private fun installId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_INSTALL_ID, null)?.let { return it }

            return UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_INSTALL_ID, it).apply()
            }
        }
    }
}
