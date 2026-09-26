package gg.padu.httpmonitor.report

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.UUID

/**
 * Identifies the install a batch came from, and the app it is an install of.
 *
 * [uid] must stay the same across launches — the dashboard groups traffic by it,
 * and uses it to recognise a retried batch.
 *
 * One ingest key covers every app of a project, so the dashboard needs to know
 * which of them a batch belongs to. That is [packageName], read from the build;
 * set [tag] when one package ships as several things worth telling apart — a
 * staging flavour, a white-label build, a per-tester install.
 */
class DeviceInfo @JvmOverloads constructor(
    val uid: String,
    val label: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val appBuild: String? = null,
    val packageName: String? = null,
    val appLabel: String? = null,
    val tag: String? = null,
    val platform: String = PLATFORM_ANDROID
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

    /**
     * The app this install is of. Sent alongside [toJson] so the dashboard can
     * file traffic under the right app of the project the key belongs to.
     */
    fun appJson(): JSONObject = JSONObject().apply {
        put("platform", platform)
        packageName?.let { put("package_name", it) }
        appLabel?.let { put("label", it) }
        tag?.let { put("tag", it) }
        appVersion?.let { put("version", it) }
        appBuild?.let { put("build", it) }
    }

    companion object {

        const val PLATFORM_ANDROID = "android"

        private const val PREFS = "gg.padu.httpmonitor"
        private const val KEY_INSTALL_ID = "install_id"

        /**
         * Reads the device and app names, with an install id minted once and kept
         * in the library's own preferences. Uninstalling the app resets it, which
         * is the intent: it identifies an install, not a person.
         *
         * Pass [tag] to file this build under its own app in the dashboard even
         * though it shares a package name — and an ingest key — with another.
         */
        @JvmStatic
        @JvmOverloads
        fun from(context: Context, tag: String? = null): DeviceInfo {
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
                appBuild = build?.toString(),
                packageName = app.packageName,
                appLabel = runCatching {
                    app.applicationInfo?.loadLabel(app.packageManager)?.toString()
                }.getOrNull(),
                tag = tag?.takeIf { it.isNotBlank() }
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
