package com.hk.mobile2tvgame

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.pm.ActivityInfo
import android.content.ComponentName
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Yatay kumanda ekrani. Icerik bir HTML sayfasi (assets/kumanda.html ya da kullanicinin
 * https adresi); sayfa Android.touch(...) ile dokunuslari gonderir, buradan yerel
 * kopru uzerinden (127.0.0.1:27185) yayin surecine, oradan oyuna iletilir.
 */
class KumandaActivity : Activity() {

    private val queue = LinkedBlockingQueue<String>()

    @Volatile
    private var alive = true

    private val asset = "file:///android_asset/kumanda.html"

    inner class Bridge {
        @JavascriptInterface
        fun touch(action: Int, id: Int, x: Double, y: Double) {
            queue.offer("T $action $id $x $y\n")
        }

        /** Yuklu (baslatilabilir) uygulamalar: [{"n":ad,"c":"paket/etkinlik"}, ...] */
        @JavascriptInterface
        fun apps(): String {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach {
                    arr.put(
                        JSONObject()
                            .put("n", it.loadLabel(pm).toString())
                            .put("c", ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToShortString())
                    )
                }
            return arr.toString()
        }

        /** Sanal ekranda uygulamayi ac / one getir (yayini durdurmadan). */
        @JavascriptInterface
        fun launch(comp: String) {
            if (comp.matches(Regex("[A-Za-z0-9_.$/]+"))) queue.offer("A $comp\n")
        }

        @JavascriptInterface
        fun back() {
            queue.offer("K 0 4\nK 1 4\n")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        val web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.toString() != asset) view.loadUrl(asset)
            }
        }
        val url = intent.getStringExtra("url")?.trim().orEmpty()
        web.loadUrl(if (url.startsWith("https://")) url else asset)
        setContentView(web)

        thread(isDaemon = true) { writer() }
    }

    private fun writer() {
        var s: Socket? = null
        while (alive && s == null) {
            try {
                s = Socket("127.0.0.1", 27185).also { it.tcpNoDelay = true }
            } catch (e: Exception) {
                Thread.sleep(500)
            }
        }
        val out = s?.getOutputStream() ?: return
        try {
            while (alive) {
                val line = queue.take()
                out.write(line.toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        alive = false
        queue.offer("")
        super.onDestroy()
    }
}
