package com.hk.mobile2tvgame

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
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

class KumandaActivity : Activity(), SensorEventListener {

    private val queue = LinkedBlockingQueue<String>()
    private lateinit var web: WebView
    private var sensorManager: SensorManager? = null
    private var lastTiltSentMs = 0L

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

        @JavascriptInterface
        fun hasFocusFix(): Boolean = true

        @JavascriptInterface
        fun setWindowFocusable(focusable: Boolean) {
            runOnUiThread {
                if (!alive) return@runOnUiThread
                if (focusable) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    applyImmersiveUi()
                }
            }
        }

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

        @JavascriptInterface
        fun launch(comp: String) {
            if (comp.matches(Regex("[A-Za-z0-9_.\$/]+"))) queue.offer("A $comp\n")
        }

        @JavascriptInterface
        fun back() {
            queue.offer("K 0 4\nK 1 4\n")
        }

        @JavascriptInterface
        fun refreshFromGithub() {
            syncHtmlFromGithub(forceReload = true)
        }
    }

    private fun applyImmersiveUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    @SuppressLint("SetJavaScriptEnabled", "SourceLockedOrientationActivity", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // FLAG_NOT_FOCUSABLE: TV sanal ekranina (Display 2) dokunus enjekte edildiginde
        // Android WindowManagerService'in telefon ekrani (Display 0) ile TV ekrani arasinda
        // pencere odagi (window focus) ping-pongu yapip WebView dokunuslarini iptal etmesini onler!
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        applyImmersiveUi()

        web = object : WebView(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                // Sanal ekrana dokunus gittiginde WebView odagi kaybettigini sanirsa
                // Chromium aktif dokunuslari (joystick/oklar) ACTION_CANCEL (touchcancel) ile keser.
                // Her zaman true ileterek dokunus akisinin kesilmesini engelliyoruz.
                super.onWindowFocusChanged(true)
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    // Sistem kaynakli sahte ACTION_CANCEL olayini ACTION_MOVE'a cevirerek
                    // basili tutulan joystick veya yon oklarinin kopmasini engelle!
                    val count = ev.pointerCount
                    val props = Array(count) { i ->
                        MotionEvent.PointerProperties().also { ev.getPointerProperties(i, it) }
                    }
                    val coords = Array(count) { i ->
                        MotionEvent.PointerCoords().also { ev.getPointerCoords(i, it) }
                    }
                    val moveEv = MotionEvent.obtain(
                        ev.downTime,
                        ev.eventTime,
                        MotionEvent.ACTION_MOVE,
                        count,
                        props,
                        coords,
                        ev.metaState,
                        ev.buttonState,
                        ev.xPrecision,
                        ev.yPrecision,
                        ev.deviceId,
                        ev.edgeFlags,
                        ev.source,
                        ev.flags
                    )
                    val handled = super.dispatchTouchEvent(moveEv)
                    moveEv.recycle()
                    return handled
                }
                return super.dispatchTouchEvent(ev)
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                if (Build.VERSION.SDK_INT >= 29 && (r - l) > 0 && (b - t) > 0) {
                    try {
                        systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
                    } catch (_: Exception) {}
                }
            }
        }

        web.setBackgroundColor(Color.BLACK)
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
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

        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        } catch (_: Exception) {}

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

    override fun onResume() {
        super.onResume()
        applyImmersiveUi()
        try {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (_: Exception) {}
    }

    override fun onPause() {
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTiltSentMs < 35L) return
        lastTiltSentMs = now
        @Suppress("DEPRECATION")
        val rot = windowManager.defaultDisplay.rotation
        val sign = if (rot == Surface.ROTATION_270) -1f else 1f
        val norm = (event.values[1] * sign) / 6.5f
        web.evaluateJavascript("if(window.onNativeTilt)window.onNativeTilt($norm);", null)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
