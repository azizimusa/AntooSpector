package gg.padu.ke

import android.app.Application
import gg.padu.httpmonitor.HeaderRedactingFilter
import gg.padu.httpmonitor.HttpMonitor
import gg.padu.httpmonitor.control.DeviceControl
import gg.padu.httpmonitor.report.AntooReporter
import gg.padu.httpmonitor.report.DeviceInfo

class PadukeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (!BuildConfig.DEBUG) return

        HttpMonitor
            .start(maxTransactions = 500)
            // Runs before anything is stored, so nothing sensitive reaches the
            // in-app viewer or the dashboard.
            .addFilter(HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"))

        // Set antoo.endpoint and antoo.key in local.properties to also ship
        // captured traffic to an Antoo Spector dashboard.
        if (BuildConfig.ANTOO_ENDPOINT.isNotEmpty() && BuildConfig.ANTOO_KEY.isNotEmpty()) {
            // One identity for both the reporter and Device Control, so the
            // dashboard files the traffic and the screenshots under the same
            // install.
            val device = DeviceInfo.from(this)

            HttpMonitor.report(
                AntooReporter(
                    endpoint = BuildConfig.ANTOO_ENDPOINT,
                    apiKey = BuildConfig.ANTOO_KEY,
                    device = device
                )
            )

            // Lets the dashboard's Device Control page screenshot this app's own
            // screen. Polls on its own daemon thread; captures nothing beyond the
            // app, so it needs no permission.
            DeviceControl
                .from(
                    context = this,
                    endpoint = BuildConfig.ANTOO_ENDPOINT,
                    apiKey = BuildConfig.ANTOO_KEY,
                    device = device
                )
                .start()
        }
    }
}
