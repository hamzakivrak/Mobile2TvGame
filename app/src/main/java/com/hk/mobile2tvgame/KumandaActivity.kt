package com.hk.mobile2tvgame

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Yatay kumanda ekrani.
 * Acildiginda GitHub deposundaki (hamzakivrak/Mobile2TvGame) guncel
 * app/src/main/assets/kumanda.html dosyasini otomatik ceker ve onbellege kaydeder.
 * Boylece kumanda.html degistiginde tekrar APK derlemeye gerek kalmaz.
 */
class KumandaActivity : Activity() {

    private val queue = LinkedBlockingQueue<String>()
    private lateinit var web: WebView

    @Volatile
    private var alive = true

    @Volatile
    private var lastW = 1920

    @Volatile
    private var lastH = 1080

    private val assetUrl = "file:///android_asset/kumanda.html"

    companion object {
        private const val GITHUB_API_RAW =
            "https://api.github.com/repos/hamzakivrak/Mobile2TvGame/contents/app/src/main/assets/kumanda.html"
        private const val GITHUB_CDN_RAW =
            "https://raw.githubusercontent.com/hamzakivrak/Mobile2TvGame/main/app/src/main/assets/kumanda.html"
    }

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
            if (comp.matches(Regex("[A-Za-z0-9_.\$/]+"))) queue.offer("A $comp\n")
        }

        @JavascriptInterface
        fun back() {
            queue.offer("K 0 4\nK 1 4\n")
        }

        /** Kumanda HTML'ini GitHub'dan aninda yeniden ceker. */
        @JavascriptInterface
        fun refreshFromGithub() {
            syncHtmlFromGithub(forceReload = true)
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

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("if(window.setOran)window.setOran($lastW,$lastH);", null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.toString() != assetUrl) {
                    view.loadUrl(assetUrl)
                }
            }
        }
        setContentView(web)

        val customUrl = intent.getStringExtra("url")?.trim().orEmpty()
        if (customUrl.startsWith("https://")) {
            web.loadUrl(customUrl)
        } else {
            val cached = File(filesDir, "kumanda_live.html")
            if (cached.exists() && cached.length() > 100) {
                web.loadUrl("file://${cached.absolutePath}")
            } else {
                web.loadUrl(assetUrl)
            }
            syncHtmlFromGithub(forceReload = false)
        }

        thread(isDaemon = true) { writer() }
    }

    private fun downloadText(urlStr: String, isApi: Boolean): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "Mobile2TvGame-Android")
                if (isApi) {
                    setRequestProperty("Accept", "application/vnd.github.v3.raw")
                }
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun syncHtmlFromGithub(forceReload: Boolean) {
        thread(isDaemon = true) {
            val html = downloadText(GITHUB_API_RAW, isApi = true)
                ?: downloadText("$GITHUB_CDN_RAW?t=${System.currentTimeMillis()}", isApi = false)
            if (html != null && html.contains("<html") && html.contains("</html>")) {
                val cached = File(filesDir, "kumanda_live.html")
                val old = if (cached.exists()) try { cached.readText(Charsets.UTF_8) } catch (_: Exception) { "" } else ""
                if (forceReload || html != old) {
                    try {
                        cached.writeText(html, Charsets.UTF_8)
                        runOnUiThread {
                            if (alive) {
                                web.loadUrl("file://${cached.absolutePath}")
                                Toast.makeText(this, "GitHub'dan guncel kumanda.html yuklendi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } else if (forceReload) {
                runOnUiThread {
                    if (alive) {
                        Toast.makeText(this, "GitHub'a ulasilamadi, yerel kumanda kullaniliyor", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun writer() {
        while (alive) {
            var s: Socket? = null
            try {
                s = Socket("127.0.0.1", 27185).also { it.tcpNoDelay = true }
                val inp = s.getInputStream()
                thread(isDaemon = true) {
                    try {
                        inp.bufferedReader().forEachLine { line ->
                            val p = line.trim().split(' ')
                            if (p.size >= 3 && p[0] == "D") {
                                val w = p[1].toIntOrNull() ?: return@forEachLine
                                val h = p[2].toIntOrNull() ?: return@forEachLine
                                lastW = w
                                lastH = h
                                runOnUiThread {
                                    web.evaluateJavascript("if(window.setOran)window.setOran($w,$h);", null)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                val out = BufferedOutputStream(s.getOutputStream(), 4096)
                while (alive) {
                    val first = queue.take()
                    if (!alive) break
                    if (first.isNotEmpty()) out.write(first.toByteArray())
                    while (true) {
                        val next = queue.poll() ?: break
                        if (next.isNotEmpty()) out.write(next.toByteArray())
                    }
                    out.flush()
                }
            } catch (_: Exception) {
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
            } finally {
                try { s?.close() } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        alive = false
        queue.offer("")
        super.onDestroy()
    }
}
