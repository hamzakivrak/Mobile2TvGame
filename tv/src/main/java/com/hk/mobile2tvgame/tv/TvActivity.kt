package com.hk.mobile2tvgame.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
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
 * scrcpy akisini cozup ekrana basar.
 *
 * Akis duzeni (scrcpy 5.0.1, gozlenen):
 *  baslik 69 bayt: 1 dummy + 64 cihaz adi + 4 codec
 *  sonra 12 baytlik paketler:
 *    ust bit 1 ise oturum paketi: 0x80000000, genislik, yukseklik
 *    degilse medya paketi: 8 bayt (bayrak+pts), 4 bayt boyut, ardindan veri
 *      bit 62 = yapilandirma (SPS/PPS), bit 61 = anahtar kare
 */
class TvActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var statusView: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorView: CursorView

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var mcLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        cursorView = CursorView(this)
        root.addView(cursorView, FrameLayout.LayoutParams(-1, -1))
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(40, 30, 40, 30)
            setBackgroundColor(0x88000000.toInt())
            text = "Baslatiliyor..."
        }
        root.addView(statusView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        setContentView(root)
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
                    s.connect(InetSocketAddress(target.first, target.second), 5000)
                    status("Baglandi, yayin bekleniyor...")
                    startCursor(target.first)
                    stream(s)
                }
            } catch (e: Exception) {
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
            while (running) {
                inp.readFully(h)
                val bb = ByteBuffer.wrap(h)
                if ((h[0].toInt() and 0x80) != 0) {
                    val w = bb.getInt(4)
                    val ht = bb.getInt(8)
                    dec?.release()
                    val sf = surface ?: return
                    dec = Decoder(w, ht, sf)
                    status(null)
                } else {
                    val flags = bb.getLong(0)
                    val size = bb.getInt(8)
                    if (size < 0 || size > 8_000_000) throw IOException("gecersiz paket boyutu: $size")
                    val payload = ByteArray(size)
                    inp.readFully(payload)
                    val config = (flags and (1L shl 62)) != 0L
                    val pts = flags and ((1L shl 61) - 1)
                    dec?.feed(payload, pts, config)
                }
            }
        } finally {
            dec?.release()
            status("Yayin bitti, telefon araniyor...")
        }
    }
}
