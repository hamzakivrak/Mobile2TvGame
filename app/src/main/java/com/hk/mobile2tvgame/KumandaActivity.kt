package com.hk.mobile2tvgame

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class KumandaActivity : Activity(), SensorEventListener {

    private val queue = LinkedBlockingQueue<String>()
    private lateinit var rootLayout: FrameLayout
    private lateinit var previewSurfaceView: SurfaceView
    private lateinit var web: WebView
    private var sensorManager: SensorManager? = null
    private var lastTiltSentMs = 0L

    @Volatile
    private var alive = true

    @Volatile
    private var lastW = 1920

    @Volatile
    private var lastH = 1080

    @Volatile
    private var previewEnabled = false

    @Volatile
    private var previewSurface: Surface? = null

    @Volatile
    private var previewSocket: Socket? = null

    private var previewNx = 0.0
    private var previewNy = 0.0
    private var previewNw = 1.0
    private var previewNh = 1.0

    private val assetUrl = "file:///android_asset/kumanda.html"

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            streamStatusText = if (result == PackageManager.PERMISSION_GRANTED) {
                "Shizuku izni verildi."
            } else {
                "Shizuku izni reddedildi."
            }
            pushStateToWeb()
        }
    }

    companion object {
        private const val GITHUB_API_RAW =
            "https://api.github.com/repos/hamzakivrak/Mobile2TvGame/contents/app/src/main/assets/kumanda.html"
        private const val GITHUB_CDN_RAW =
            "https://raw.githubusercontent.com/hamzakivrak/Mobile2TvGame/main/app/src/main/assets/kumanda.html"

        private val iconCache = ConcurrentHashMap<String, String>()

        @Volatile
        private var streamProc: Process? = null

        @Volatile
        private var tvConnected: Boolean = false

        @Volatile
        private var lastDisplaySpec: String = "1920x1080/360"

        @Volatile
        private var lastScrcpyVer: String = "5.0.1"

        @Volatile
        private var streamStatusText: String = "Hazir."

        private val logLines = ArrayDeque<String>(60)
        private val logLock = Any()

        private fun appendLog(line: String) {
            synchronized(logLock) {
                if (logLines.size >= 50) logLines.removeFirst()
                logLines.addLast(line)
            }
        }

        private fun clearLogs() {
            synchronized(logLock) {
                logLines.clear()
            }
        }

        private fun getLogsString(): String {
            synchronized(logLock) {
                return logLines.joinToString("\n")
            }
        }
    }

    private class LocalVideoDecoder(width: Int, height: Int, surface: Surface) {
        private val codec: MediaCodec = MediaCodec.createDecoderByType("video/avc")

        @Volatile
        private var active = true
        private val outThread: Thread

        init {
            val f = MediaFormat.createVideoFormat("video/avc", width, height)
            if (Build.VERSION.SDK_INT >= 30) {
                f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            codec.configure(f, surface, null, 0)
            codec.start()
            outThread = thread(isDaemon = true) {
                val info = MediaCodec.BufferInfo()
                while (active) {
                    try {
                        var i = codec.dequeueOutputBuffer(info, 10_000)
                        while (i >= 0 && active) {
                            codec.releaseOutputBuffer(i, info.size > 0)
                            i = codec.dequeueOutputBuffer(info, 0)
                        }
                    } catch (_: Exception) {
                        if (!active) break
                    }
                }
            }
        }

        fun feed(data: ByteArray, size: Int, ptsUs: Long, config: Boolean) {
            val i = codec.dequeueInputBuffer(50_000)
            if (i < 0) return
            val buf = codec.getInputBuffer(i) ?: return
            buf.clear()
            buf.put(data, 0, size)
            codec.queueInputBuffer(i, 0, size, ptsUs, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        }

        fun release() {
            active = false
            try { outThread.join(250) } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    private fun pushStateToWeb() {
        runOnUiThread {
            if (!alive || !::web.isInitialized) return@runOnUiThread
            try {
                web.evaluateJavascript("if(window.onStreamStateChanged)window.onStreamStateChanged();", null)
            } catch (_: Exception) {}
        }
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
        fun setPreviewRect(nx: Double, ny: Double, nw: Double, nh: Double, enabled: Boolean) {
            previewNx = nx.coerceIn(0.0, 1.0)
            previewNy = ny.coerceIn(0.0, 1.0)
            previewNw = nw.coerceIn(0.05, 1.0)
            previewNh = nh.coerceIn(0.05, 1.0)
            val wasEnabled = previewEnabled
            previewEnabled = enabled
            if (!enabled && wasEnabled) {
                try { previewSocket?.close() } catch (_: Exception) {}
            }
            runOnUiThread {
                if (!alive) return@runOnUiThread
                updatePreviewLayout()
            }
        }

        @JavascriptInterface
        fun shizukuState(): String {
            val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
            val granted = running && try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
            val streaming = streamProc != null
            return JSONObject()
                .put("running", running)
                .put("granted", granted)
                .put("streaming", streaming)
                .put("tvConnected", streaming && tvConnected)
                .put("status", streamStatusText)
                .put("logs", getLogsString())
                .toString()
        }

        @JavascriptInterface
        fun requestShizuku(): String {
            return try {
                if (!Shizuku.pingBinder()) {
                    streamStatusText = "Shizuku calismiyor! Once Shizuku'yu acip Baslat'a basin."
                    pushStateToWeb()
                    "not_running"
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    streamStatusText = "Shizuku hazir (izin var)."
                    pushStateToWeb()
                    "granted"
                } else {
                    streamStatusText = "Shizuku izni isteniyor..."
                    Shizuku.requestPermission(100)
                    pushStateToWeb()
                    "requested"
                }
            } catch (e: Throwable) {
                streamStatusText = "Shizuku hatasi: ${e.message}"
                pushStateToWeb()
                "error"
            }
        }

        @JavascriptInterface
        fun startStream(display: String, appPkg: String, version: String): String {
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    streamStatusText = "Once Shizuku iznini verin!"
                    pushStateToWeb()
                    return "no_perm"
                }
                if (streamProc != null) {
                    streamStatusText = if (tvConnected) "TV Bagli (Yayin Aktif)" else "TV Bekleniyor... (TV'de uygulamayi acin)"
                    pushStateToWeb()
                    return "already_running"
                }
                val apk = applicationInfo.sourceDir
                val v = version.trim().ifEmpty { "5.0.1" }
                val d = display.trim().ifEmpty { "1920x1080/360" }
                lastDisplaySpec = d
                lastScrcpyVer = v

                val a0 = appPkg.trim()
                val a = if (a0.isEmpty() || a0.contains('/')) a0 else (
                        try {
                            packageManager.getLaunchIntentForPackage(a0)?.component?.flattenToShortString()
                        } catch (_: Exception) {
                            null
                        } ?: a0)

                clearLogs()
                tvConnected = false
                streamStatusText = "Yayin baslatildi, TV bekleniyor..."
                appendLog("Sanal ekran: $d | Uygulama: ${a.ifEmpty { "(yok)" }}")
                pushStateToWeb()

                thread(isDaemon = true) {
                    try {
                        val m = Shizuku::class.java.getDeclaredMethod(
                            "newProcess",
                            Array<String>::class.java, Array<String>::class.java, String::class.java
                        )
                        m.isAccessible = true
                        // Onceki oturumdan kalan zombi RelayMain / scrcpy sureclerini temizle:
                        val sh = "pkill -f com.hk.mobile2tvgame.RelayMain >/dev/null 2>&1; " +
                                "pkill -f com.genymobile.scrcpy.Server >/dev/null 2>&1; " +
                                "sleep 0.2; " +
                                "export CLASSPATH='$apk'; exec /system/bin/app_process / " +
                                "com.hk.mobile2tvgame.RelayMain '$apk' '$v' '$a' '$d' 27183 2>&1"
                        val p = m.invoke(null, arrayOf("sh", "-c", sh), null, null) as Process
                        streamProc = p
                        pushStateToWeb()
                        p.inputStream.bufferedReader().forEachLine { line ->
                            appendLog(line)
                            if (line.contains("TV baglandi") || line.contains("Sanal ekran hazir") || line.contains("[akis] video boyutu")) {
                                tvConnected = true
                                streamStatusText = "TV Baglandi (Yayin Aktif)"
                            } else if (line.contains("TV bekleniyor")) {
                                tvConnected = false
                                streamStatusText = "TV Bekleniyor... (TV'de uygulamayi acin)"
                            } else if (line.contains("[beklemede]")) {
                                streamStatusText = "TV Bekleniyor (Baglaninca uygulama TV'de acilacak)"
                            }
                            pushStateToWeb()
                        }
                        appendLog("(Yayin sureci sonlandi)")
                        tvConnected = false
                        streamStatusText = "Yayin durdu."
                        streamProc = null
                        pushStateToWeb()
                    } catch (e: Throwable) {
                        appendLog("HATA: $e")
                        tvConnected = false
                        streamStatusText = "Hata: ${e.message}"
                        streamProc = null
                        pushStateToWeb()
                    }
                }
                return "started"
            } catch (e: Throwable) {
                streamStatusText = "Baslatma hatasi: ${e.message}"
                pushStateToWeb()
                return "error"
            }
        }

        @JavascriptInterface
        fun stopStream() {
            try { streamProc?.destroy() } catch (_: Throwable) {}
            streamProc = null
            tvConnected = false
            thread(isDaemon = true) {
                try {
                    val m = Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java, Array<String>::class.java, String::class.java
                    )
                    m.isAccessible = true
                    val p = m.invoke(
                        null,
                        arrayOf("sh", "-c", "pkill -f com.hk.mobile2tvgame.RelayMain >/dev/null 2>&1; pkill -f com.genymobile.scrcpy.Server >/dev/null 2>&1"),
                        null,
                        null
                    ) as Process
                    p.waitFor()
                } catch (_: Throwable) {}
            }
            streamStatusText = "Yayin durduruldu."
            appendLog("Kullanici tarafindan durduruldu.")
            pushStateToWeb()
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
                    val ai = it.activityInfo.applicationInfo
                    @Suppress("DEPRECATION")
                    val isGame = (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) ||
                            ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
                    arr.put(
                        JSONObject()
                            .put("n", it.loadLabel(pm).toString())
                            .put("c", ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToShortString())
                            .put("g", isGame)
                    )
                }
            return arr.toString()
        }

        @JavascriptInterface
        fun appIcon(comp: String): String {
            iconCache[comp]?.let { return it }
            return try {
                val pm = packageManager
                val cn = ComponentName.unflattenFromString(comp)
                val drawable = if (cn != null) {
                    try { pm.getActivityIcon(cn) } catch (_: Exception) { pm.getApplicationIcon(cn.packageName) }
                } else {
                    pm.getApplicationIcon(comp.substringBefore('/'))
                }
                val size = 96
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                val bos = ByteArrayOutputStream(2048)
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
                bmp.recycle()
                val dataUrl = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                iconCache[comp] = dataUrl
                dataUrl
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun launch(comp: String) {
            if (!comp.matches(Regex("[A-Za-z0-9_.\$/]+"))) return
            // Eger yayin henuz baslatilmadiysa otomatik olarak bu uygulamayla yayini baslat
            if (streamProc == null) {
                val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
                val granted = running && try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
                if (granted) {
                    startStream(lastDisplaySpec, comp, lastScrcpyVer)
                    return
                }
            }
            queue.offer("A $comp\n")
        }

        @JavascriptInterface
        fun back() {
            queue.offer("K 0 4\nK 1 4\n")
        }

        @JavascriptInterface
        fun exitKumanda() {
            runOnUiThread {
                finishAffinity()
            }
        }

        @JavascriptInterface
        fun openDevSettings() {
            runOnUiThread {
                openDeveloperOptions(this@KumandaActivity)
            }
        }

        @JavascriptInterface
        fun openShizuku() {
            runOnUiThread {
                openShizukuApp(this@KumandaActivity)
            }
        }

        @JavascriptInterface
        fun refreshFromGithub() {
            syncHtmlFromGithub(forceReload = true)
        }
    }

    private fun openDeveloperOptions(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    private fun openShizukuApp(ctx: Context) {
        val pkg = "moe.shizuku.privileged.api"
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
            } else {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Exception) {}
    }

    private fun updatePreviewLayout() {
        val rw = rootLayout.width
        val rh = rootLayout.height
        if (rw <= 0 || rh <= 0) return
        if (!previewEnabled) {
            previewSurfaceView.visibility = View.INVISIBLE
            return
        }
        previewSurfaceView.visibility = View.VISIBLE
        val left = (previewNx * rw).toInt()
        val top = (previewNy * rh).toInt()
        val w = (previewNw * rw).toInt().coerceAtLeast(10)
        val h = (previewNh * rh).toInt().coerceAtLeast(10)
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START)
        lp.leftMargin = left
        lp.topMargin = top
        previewSurfaceView.layoutParams = lp
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
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        applyImmersiveUi()

        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (_: Throwable) {}

        rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(Color.BLACK)

        previewSurfaceView = SurfaceView(this)
        previewSurfaceView.visibility = View.INVISIBLE
        previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurface = holder.surface
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                previewSurface = holder.surface
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewSurface = null
                try { previewSocket?.close() } catch (_: Exception) {}
            }
        })
        rootLayout.addView(previewSurfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.START))

        web = object : WebView(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(true)
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
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
                if (changed) {
                    post { updatePreviewLayout() }
                }
            }
        }

        web.setBackgroundColor(Color.TRANSPARENT)
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
                view.evaluateJavascript("if(window.onStreamStateChanged)window.onStreamStateChanged();", null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.toString() != assetUrl) {
                    view.loadUrl(assetUrl)
                }
            }
        }
        rootLayout.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootLayout)

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
        thread(isDaemon = true) { previewLoop() }
    }

    private fun previewLoop() {
        val h = ByteArray(12)
        val bb = ByteBuffer.wrap(h)
        var payload = ByteArray(256 * 1024)
        while (alive) {
            if (!previewEnabled || previewSurface == null) {
                try { Thread.sleep(150) } catch (_: InterruptedException) {}
                continue
            }
            var s: Socket? = null
            var dec: LocalVideoDecoder? = null
            try {
                s = Socket("127.0.0.1", 27188).also {
                    it.tcpNoDelay = true
                    it.receiveBufferSize = 128 * 1024
                }
                previewSocket = s
                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 32768))
                while (alive && previewEnabled) {
                    val sf = previewSurface ?: break
                    inp.readFully(h)
                    if ((h[0].toInt() and 0x80) != 0) {
                        val w = bb.getInt(4)
                        val ht = bb.getInt(8)
                        if (w > 0 && ht > 0) {
                            dec?.release()
                            dec = LocalVideoDecoder(w, ht, sf)
                        }
                    } else {
                        val flags = bb.getLong(0)
                        val size = bb.getInt(8)
                        if (size < 0 || size > 8_000_000) throw IOException("gecersiz boyut: $size")
                        if (size > payload.size) payload = ByteArray(size)
                        inp.readFully(payload, 0, size)
                        val config = (flags and (1L shl 62)) != 0L
                        val pts = flags and ((1L shl 61) - 1)
                        if (dec == null) {
                            dec = LocalVideoDecoder(lastW, lastH, sf)
                        }
                        dec.feed(payload, size, pts, config)
                    }
                }
            } catch (_: Exception) {
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
            } finally {
                previewSocket = null
                try { s?.close() } catch (_: Exception) {}
                try { dec?.release() } catch (_: Exception) {}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveUi()
        pushStateToWeb()
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
        previewEnabled = false
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
        try { previewSocket?.close() } catch (_: Exception) {}
        queue.offer("")
        super.onDestroy()
    }
}