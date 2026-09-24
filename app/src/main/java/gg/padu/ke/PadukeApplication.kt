package gg.padu.ke

import android.app.Application
import gg.padu.httpmonitor.HeaderRedactingFilter
import gg.padu.httpmonitor.HttpMonitor

class PadukeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            HttpMonitor
                .start(maxTransactions = 500)
                .addFilter(HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"))
        }
    }
}
