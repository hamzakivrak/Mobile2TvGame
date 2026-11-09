package com.hk.mobile2tvgame.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Telefonun UDP duyurusunu (port 27184) dinler, bulunca TCP ile baglanir,
 * scrcpy video akisini (port 27183), imlec akisini (port 27186) ve
 * PCM ses akisini (port 27187) alip oynatir.
 */
class TvActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var rootLayout: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorView: CursorView

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var running = false

    @Volatile
    private var streamActive = false

    private var worker: Thread? = null
    private var mcLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(Color.BLACK)
        surfaceView = SurfaceView(this)
        rootLayout.addView(surfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        cursorView = CursorView(this)
        rootLayout.addView(cursorView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(40, 30, 40, 30)
            setBackgroundColor(0x88000000.toInt())
            text = "Baslatiliyor..."
        }
        rootLayout.addView(statusView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        setContentView(rootLayout)
        surfaceView.holder.addCallback(this)

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            mcLock = wm.createMulticastLock("m2t").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try { mcLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startWorker()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopWorker()
        surface = null
    }

    private fun updateAspectRatio(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        runOnUiThread {
            val rw = rootLayout.width
            val rh = rootLayout.height
            if (rw <= 0 || rh <= 0) return@runOnUiThread
            val videoRatio = videoW.toFloat() / videoH.toFloat()
            val screenRatio = rw.toFloat() / rh.toFloat()
            val fitW: Int
            val fitH: Int
            if (videoRatio > screenRatio) {
                fitW = rw
                fitH = (rw / videoRatio).toInt()
            } else {
                fitH = rh
                fitW = (rh * videoRatio).toInt()
            }
            surfaceView.layoutParams = FrameLayout.LayoutParams(fitW, fitH, Gravity.CENTER)
            cursorView.layoutParams = FrameLayout.LayoutParams(fitW, fitH, Gravity.CENTER)
        }
    }

    private fun status(text: String?) {
        runOnUiThread {
            if (text.isNullOrEmpty()) {
                statusView.visibility = View.GONE
            } else {
                statusView.visibility = View.VISIBLE
                statusView.text = text
            }
        }
    }

    private fun startWorker() {
        if (running) return
        running = true
        worker = thread { loop() }
    }

    private fun stopWorker() {
        running = false
        streamActive = false
        try { worker?.join(1500) } catch (_: Exception) {}
        worker = null
    }

    private fun loop() {
        while (running) {
            try {
                status("Telefon araniyor... (telefonda Yayini baslat'a basin)")
                val target = discover() ?: continue
                status("Baglaniliyor: ${target.first}:${target.second}")
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 256 * 1024
                    s.connect(InetSocketAddress(target.first, target.second), 5000)
                    status("Baglandi, yayin bekleniyor...")
                    streamActive = true
                    startCursor(target.first)
                    startAudio(target.first)
                    stream(s)
                }
            } catch (e: Exception) {
                streamActive = false
                if (running) status("Hata: ${e.message}")
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            }
        }
    }

    /** Telefonun imlec kanalina (port 27186) baglanir; satir: "C id action x y" */
    private fun startCursor(host: String) {
        thread(isDaemon = true) {
            try {
                Socket().use { cs ->
                    cs.connect(InetSocketAddress(host, 27186), 3000)
                    cs.tcpNoDelay = true
                    cs.getInputStream().bufferedReader().forEachLine { l ->
                        if (!streamActive) return@forEachLine
                        try {
                            val p = l.split(' ')
                            if (p.size >= 5 && p[0] == "C") {
                                cursorView.update(p[1].toInt(), p[2].toInt(), p[3].toFloat(), p[4].toFloat())
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Telefonun ses kanalina (port 27187) baglanir ve 48kHz 16-bit stereo PCM oynatir. */
    private fun startAudio(host: String) {
        thread(isDaemon = true) {
            var track: AudioTrack? = null
            try {
                val sampleRate = 48000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(8192)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                track = if (Build.VERSION.SDK_INT >= 26) {
                    AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(minBuf * 2)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()
                } else {
                    AudioTrack(attrs, fmt, minBuf * 2, AudioTrack.MODE_STREAM, 0)
                }
                track.play()

                Socket().use { asock ->
                    asock.connect(InetSocketAddress(host, 27187), 3000)
                    asock.tcpNoDelay = true
                    val inp = DataInputStream(BufferedInputStream(asock.getInputStream(), 16384))
                    val pcm = ByteArray(4096)
                    while (running && streamActive) {
                        inp.readFully(pcm, 0, pcm.size)
                        track.write(pcm, 0, pcm.size)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun discover(): Pair<String, Int>? {
        DatagramSocket(null).use { ds ->
            ds.reuseAddress = true
            ds.bind(InetSocketAddress(27184))
            ds.soTimeout = 1000
            val buf = ByteArray(64)
            while (running) {
                try {
                    val pk = DatagramPacket(buf, buf.size)
                    ds.receive(pk)
                    val t = String(pk.data, 0, pk.length)
                    if (t.startsWith("M2T1:")) {
                        val host = pk.address.hostAddress ?: continue
                        return Pair(host, t.substring(5).trim().toInt())
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }

    private fun stream(s: Socket) {
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val hdr = ByteArray(69)
        inp.readFully(hdr)
        var dec: Decoder? = null
        try {
            val h = ByteArray(12)
            val bb = ByteBuffer.wrap(h)
            var payload = ByteArray(256 * 1024)
            while (running) {
                inp.readFully(h)
                if ((h[0].toInt() and 0x80) != 0) {
                    val w = bb.getInt(4)
                    val ht = bb.getInt(8)
                    dec?.release()
                    updateAspectRatio(w, ht)
                    val sf = surface ?: return
                    dec = Decoder(w, ht, sf)
                    status(null)
                } else {
                    val flags = bb.getLong(0)
                    val size = bb.getInt(8)
                    if (size < 0 || size > 8_000_000) throw IOException("gecersiz paket boyutu: $size")
                    if (size > payload.size) payload = ByteArray(size)
                    inp.readFully(payload, 0, size)
                    val config = (flags and (1L shl 62)) != 0L
                    val pts = flags and ((1L shl 61) - 1)
                    dec?.feed(payload, size, pts, config)
                }
            }
        } finally {
            streamActive = false
            dec?.release()
            status("Yayin bitti, telefon araniyor...")
        }
    }
}
