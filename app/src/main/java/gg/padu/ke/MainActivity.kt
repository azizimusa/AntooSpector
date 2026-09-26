package gg.padu.ke

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import gg.padu.httpmonitor.HttpMonitor
import gg.padu.httpmonitor.okhttp.HttpMonitorInterceptor
import gg.padu.httpmonitor.urlconnection.UrlInstrument
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Demo screen: fires traffic through both instrumented paths and opens the monitor. */
class MainActivity : AppCompatActivity() {

    private val io = Executors.newCachedThreadPool()

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpMonitorInterceptor())
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.sendOkHttp).setOnClickListener { sendWithOkHttp() }
        findViewById<Button>(R.id.sendUrlConnection).setOnClickListener { sendWithUrlConnection() }
        findViewById<Button>(R.id.openMonitor).setOnClickListener { HttpMonitor.show(this) }
        findViewById<Button>(R.id.showDialog).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete this install?")
                .setMessage("A dialog is its own window over the activity. Device Control captures it too.")
                .setPositiveButton("Delete", null)
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun sendWithOkHttp() = io.execute {
        runCatching {
            val request = Request.Builder().url("https://httpbin.org/get?via=okhttp").build()
            client.newCall(request).execute().use { it.body?.string() }
        }.onFailure { report(it) }
    }

    private fun sendWithUrlConnection() = io.execute {
        runCatching {
            val connection = UrlInstrument
                .openConnection(URL("https://httpbin.org/get?via=urlconnection")) as HttpURLConnection
            try {
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { report(it) }
    }

    private fun report(error: Throwable) = runOnUiThread {
        Toast.makeText(this, error.message ?: error.toString(), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }
}
