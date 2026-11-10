package com.hk.mobile2tvgame

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.random.Random

object RelayMain {

    private const val BEACON_PORT = 27184
    private const val LOCAL_CTL_PORT = 27185
    private const val CURSOR_PORT = 27186
    private const val AUDIO_PORT = 27187
    private const val LOCAL_VIDEO_PORT = 27188

    @Volatile
    private var displayId = -1

    @Volatile
    private var serverProc: Process? = null

    @Volatile
    private var beacon = true

    @Volatile
    private var ctlOut: OutputStream? = null
    private val ctlLock = Any()

    @Volatile
    private var vw = 1920

    @Volatile
    private var vh = 1080

    @Volatile
    private var touchLogged = false

    @Volatile
    private var curOut: OutputStream? = null
    private val curQueue = LinkedBlockingQueue<String>(64)

    @Volatile
    private var audOut: OutputStream? = null
    private val audLock = Any()

    // Telefonda canli ekran onizlemesi (KumandaActivity SurfaceView) icin yerel video akisi
    @Volatile
    private var previewActive = false
    private val previewQueue = LinkedBlockingQueue<ByteArray>(24)
    private val previewCacheLock = Any()

    @Volatile
    private var latestSessionHdr: ByteArray? = null

    @Volatile
    private var latestConfigPacket: ByteArray? = null

    @Volatile
    private var latestKeyPacket: ByteArray? = null

    private val localClients = CopyOnWriteArrayList<OutputStream>()

    private fun startCursorServer() {
        thread(isDaemon = true) {
            while (true) {
                val msg = curQueue.take()
                val o = curOut ?: continue
                try {
                    o.write(msg.toByteArray())
                    while (true) {
                        val next = curQueue.poll() ?: break
                        o.write(next.toByteArray())
                    }
                    o.flush()
                } catch (_: Exception) {
                    curOut = null
                }
            }
        }
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(CURSOR_PORT))
                while (true) {
                    val c = ss.accept()
                    c.tcpNoDelay = true
                    curOut = BufferedOutputStream(c.getOutputStream(), 4096)
                }
            } catch (e: Exception) {
                p("Imlec sunucusu hatasi: $e")
            }
        }
    }

    private fun startAudioServer() {
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(AUDIO_PORT))
                while (true) {
                    val c = ss.accept()
                    c.tcpNoDelay = true
                    synchronized(audLock) {
                        audOut = BufferedOutputStream(c.getOutputStream(), 16384)
                    }
                    p("TV ses kanali baglandi.")
                }
            } catch (e: Exception) {
                p("Ses sunucusu hatasi: $e")
            }
        }
    }

    private fun startLocalVideoServer() {
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket(LOCAL_VIDEO_PORT, 2, InetAddress.getByName("127.0.0.1"))
                while (true) {
                    val c = ss.accept()
                    thread(isDaemon = true) {
                        try {
                            c.tcpNoDelay = true
                            c.sendBufferSize = 128 * 1024
                            val out = BufferedOutputStream(c.getOutputStream(), 32768)
                            previewQueue.clear()
                            synchronized(previewCacheLock) {
                                val sh = latestSessionHdr ?: ByteBuffer.allocate(12).apply {
                                    put(0, 0x80.toByte())
                                    putInt(4, vw)
                                    putInt(8, vh)
                                }.array()
                                out.write(sh)
                                latestConfigPacket?.let { out.write(it) }
                                latestKeyPacket?.let { out.write(it) }
                                out.flush()
                            }
                            previewActive = true
                            while (previewActive && !c.isClosed) {
                                val pkt = previewQueue.take()
                                out.write(pkt)
                                while (true) {
                                    val next = previewQueue.poll() ?: break
                                    out.write(next)
                                }
                                out.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            previewActive = false
                            previewQueue.clear()
                            try { c.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                p("Yerel video onizleme sunucusu hatasi: $e")
            }
        }
    }

    private fun sendCursor(action: Int, id: Int, nx: Double, ny: Double) {
        if (curOut == null) return
        val line = "C $id $action $nx $ny\n"
        if (!curQueue.offer(line)) {
            curQueue.poll()
            curQueue.offer(line)
        }
    }

    private fun notifyDisplaySize(w: Int, h: Int) {
        val msg = "D $w $h\n".toByteArray()
        for (out in localClients) {
            try {
                out.write(msg)
                out.flush()
            } catch (_: Exception) {
                localClients.remove(out)
            }
        }
    }

    private fun sendKey(action: Int, keycode: Int) {
        val o = ctlOut ?: return
        val b = ByteBuffer.allocate(14)
        b.put(0.toByte())
        b.put(action.toByte())
        b.putInt(keycode)
        b.putInt(0)
        b.putInt(0)
        synchronized(ctlLock) {
            try {
                o.write(b.array())
                o.flush()
            } catch (e: Exception) {
                ctlOut = null
                p("Kontrol yazma hatasi: $e")
            }
        }
    }

    private fun runCmd(vararg c: String): String {
        val pr = ProcessBuilder(*c).redirectErrorStream(true).start()
        val t = pr.inputStream.bufferedReader().readText()
        pr.waitFor()
        return t
    }

    private fun launchApp(target: String) {
        var comp = target
        if (!comp.contains('/')) {
            try {
                val r = runCmd(
                    "/system/bin/cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", target
                )
                val last = r.lines().map { it.trim() }.lastOrNull { it.contains('/') && !it.contains(' ') }
                if (last != null) comp = last
            } catch (e: Exception) {
                p("cozum hatasi: $e")
            }
        }
        p("Baslatilan bilesen: $comp")
        val cmd = mutableListOf(
            "/system/bin/am", "start", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"
        )
        if (comp.contains('/')) { cmd.add("-n"); cmd.add(comp) } else { cmd.add("-p"); cmd.add(comp) }
        runCmd(*cmd.toTypedArray()).lines().filter { it.isNotBlank() }.forEach { p("[am] $it") }
    }

    private fun sendTouch(action: Int, id: Int, nx: Double, ny: Double) {
        val o = ctlOut ?: return
        val curW = vw
        val curH = vh
        val x = (nx.coerceIn(0.0, 1.0) * (curW - 1)).toInt()
        val y = (ny.coerceIn(0.0, 1.0) * (curH - 1)).toInt()
        val b = ByteBuffer.allocate(32)
        b.put(2.toByte())
        b.put(action.toByte())
        b.putLong(id.toLong())
        b.putInt(x)
        b.putInt(y)
        b.putShort(curW.toShort())
        b.putShort(curH.toShort())
        b.putShort(if (action == 1) 0.toShort() else (-1).toShort())
        b.putInt(0)
        b.putInt(0)
        synchronized(ctlLock) {
            try {
                o.write(b.array())
                o.flush()
                if (!touchLogged) {
                    touchLogged = true
                    p("[kumanda] ilk dokunus gonderildi: a=$action id=$id x=$x y=$y (${curW}x${curH})")
                }
            } catch (e: Exception) {
                ctlOut = null
                p("Kontrol yazma hatasi: $e")
            }
        }
    }

    private fun startLocalControl() {
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket(LOCAL_CTL_PORT, 4, InetAddress.getByName("127.0.0.1"))
                while (true) {
                    val c = ss.accept()
                    thread(isDaemon = true) {
                        var clientOut: OutputStream? = null
                        try {
                            c.tcpNoDelay = true
                            clientOut = c.getOutputStream()
                            localClients.add(clientOut)
                            clientOut.write("D $vw $vh\n".toByteArray())
                            clientOut.flush()
                            c.getInputStream().bufferedReader().forEachLine { l ->
                                try {
                                    val t = l.trim().split(' ')
                                    when {
                                        t.size >= 5 && t[0] == "T" -> {
                                            val a = t[1].toInt()
                                            val id = t[2].toInt()
                                            val nx = t[3].toDouble()
                                            val ny = t[4].toDouble()
                                            if (a in 0..2) {
                                                sendTouch(a, id, nx, ny)
                                            }
                                            sendCursor(a, id, nx, ny)
                                        }
                                        t.size >= 2 && t[0] == "A" -> {
                                            val comp = t[1]
                                            thread(isDaemon = true) {
                                                try { launchApp(comp) } catch (e: Exception) { p("am hatasi: $e") }
                                            }
                                        }
                                        t.size >= 3 && t[0] == "K" -> sendKey(t[1].toInt(), t[2].toInt())
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            if (clientOut != null) localClients.remove(clientOut)
                        }
                    }
                }
            } catch (e: Exception) {
                p("Yerel kontrol sunucusu hatasi: $e")
            }
        }
    }

    private fun p(s: String) {
        println(s)
        System.out.flush()
    }

    private fun startBeacon(port: Int) {
        thread(isDaemon = true) {
            val msg = "M2T1:$port".toByteArray()
            val s = DatagramSocket()
            s.broadcast = true
            while (beacon) {
                try {
                    val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
                    for (ni in ifs) {
                        if (!ni.isUp || ni.isLoopback) continue
                        for (ia in ni.interfaceAddresses) {
                            val b = ia.broadcast ?: continue
                            s.send(DatagramPacket(msg, msg.size, b, BEACON_PORT))
                        }
                    }
                } catch (_: Exception) {
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
            s.close()
        }
    }

    private fun connectLocal(scid: String, timeoutMs: Long): LocalSocket? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress("scrcpy_$scid", LocalSocketAddress.Namespace.ABSTRACT))
                return s
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }
        return null
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Runtime.getRuntime().addShutdownHook(Thread {
            try { serverProc?.destroy() } catch (_: Throwable) {}
        })
        var sock: LocalSocket? = null
        var audSock: LocalSocket? = null
        try {
            val apk = args[0]
            val version = args[1]
            val startApp = args[2]
            val display = args[3]
            val port = args[4].toInt()
            Regex("(\\d+)x(\\d+)").find(display)?.let {
                vw = it.groupValues[1].toInt()
                vh = it.groupValues[2].toInt()
            }
            startLocalControl()
            startCursorServer()
            startAudioServer()
            startLocalVideoServer()

            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            startBeacon(port)
            p("TV bekleniyor (port $port)... TV uygulamasini acin.")
            val tv = ss.accept()
            beacon = false
            tv.tcpNoDelay = true
            tv.sendBufferSize = 256 * 1024
            p("TV baglandi: ${tv.inetAddress.hostAddress}")
            val out = BufferedOutputStream(tv.getOutputStream(), 65536)

            val dst = "/data/local/tmp/m2t-scrcpy-server.jar"
            ZipFile(apk).use { z ->
                val e = z.getEntry("assets/scrcpy-server.jar")
                    ?: throw IOException("apk icinde assets/scrcpy-server.jar yok")
                z.getInputStream(e).use { i -> File(dst).outputStream().use { o -> i.copyTo(o) } }
            }

            // Giris/orta seviye islemciler (orn. Snapdragon 680 - Redmi Pad SE) icin
            // cozunurluge gore bitrate ve FPS'i otomatik optimize et:
            val maxDim = maxOf(vw, vh)
            val bitRate = when {
                maxDim <= 1366 -> 4_000_000   // 720p: 4 Mbps (Snapdragon 680 ve 2.4GHz Wi-Fi icin cok akici)
                maxDim <= 1680 -> 6_500_000   // 900p: 6.5 Mbps
                else -> 10_000_000            // 1080p: 10 Mbps
            }
            val maxFps = when {
                maxDim <= 1366 -> 45
                maxDim <= 1680 -> 50
                else -> 60
            }

            val scid = "%08x".format(Random.nextInt(1, 0x7fffffff))
            val cmd = listOf(
                "/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", version,
                "scid=$scid", "log_level=info",
                "audio=true", "audio_codec=raw", "audio_source=output",
                "control=true",
                "video_codec=h264",
                "video_bit_rate=$bitRate", "max_fps=$maxFps",
                "video_codec_options=i-frame-interval=1",
                "tunnel_forward=true", "send_dummy_byte=true",
                "new_display=$display"
            )
            val pb = ProcessBuilder(cmd)
            pb.environment()["CLASSPATH"] = dst
            pb.redirectErrorStream(true)
            val proc = pb.start()
            serverProc = proc

            thread(isDaemon = true) {
                try {
                    proc.inputStream.bufferedReader().forEachLine {
                        p("[sunucu] $it")
                        val m = Regex("New display:.*\\(id=(\\d+)\\)").find(it)
                        if (m != null) displayId = m.groupValues[1].toInt()
                    }
                } catch (_: Exception) {
                }
            }

            sock = connectLocal(scid, 10_000)
                ?: throw IOException("video soketine baglanilamadi (10 sn)")
            audSock = connectLocal(scid, 5_000)
            val ctl = connectLocal(scid, 5_000)

            if (audSock != null) {
                val aIn = DataInputStream(BufferedInputStream(audSock.inputStream, 32768))
                thread(isDaemon = true) {
                    try {
                        val codecHdr = ByteArray(4)
                        aIn.readFully(codecHdr)
                        val codecId = ByteBuffer.wrap(codecHdr).int
                        if (codecId == 0) {
                            p("UYARI: sunucu ses akisini devre disi birakti.")
                            return@thread
                        }
                        p("Ses kanali hazir (48kHz PCM).")
                        val fh = ByteArray(12)
                        var pcmBuf = ByteArray(16384)
                        while (true) {
                            aIn.readFully(fh)
                            val bb = ByteBuffer.wrap(fh)
                            val flags = bb.getLong(0)
                            val size = bb.getInt(8)
                            if (size <= 0 || size > 1_000_000) continue
                            if (size > pcmBuf.size) pcmBuf = ByteArray(size)
                            aIn.readFully(pcmBuf, 0, size)
                            val isConfig = (flags and (1L shl 62)) != 0L
                            if (!isConfig) {
                                val ao = audOut
                                if (ao != null) {
                                    synchronized(audLock) {
                                        try {
                                            ao.write(pcmBuf, 0, size)
                                            ao.flush()
                                        } catch (_: Exception) {
                                            audOut = null
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            if (ctl == null) {
                p("UYARI: kontrol soketi acilamadi (kumanda calismaz)")
            } else {
                ctlOut = ctl.outputStream
                val cin = ctl.inputStream
                thread(isDaemon = true) {
                    try {
                        val junk = ByteArray(4096)
                        while (cin.read(junk) >= 0) {
                        }
                    } catch (_: Exception) {
                    }
                }
                p("Kontrol kanali hazir.")
            }

            if (startApp.isNotBlank()) {
                thread(isDaemon = true) {
                    val waitEnd = System.currentTimeMillis() + 10_000
                    while (displayId < 0 && System.currentTimeMillis() < waitEnd) Thread.sleep(100)
                    if (displayId < 0) {
                        p("UYARI: sanal ekran numarasi bulunamadi")
                        return@thread
                    }
                    p("Uygulama sanal ekranda aciliyor: $startApp (ekran $displayId)")
                    try {
                        launchApp(startApp)
                    } catch (e: Exception) {
                        p("am hatasi: $e")
                    }
                }
            }

            val inp = DataInputStream(BufferedInputStream(sock.inputStream, 65536))
            val hdr = ByteArray(69)
            inp.readFully(hdr)
            out.write(hdr)
            out.flush()

            val h = ByteArray(12)
            var payload = ByteArray(256 * 1024)
            var total = 69L
            var last = System.currentTimeMillis()
            p("Yayin basladi.")
            while (true) {
                inp.readFully(h)
                val bb = ByteBuffer.wrap(h)
                if ((h[0].toInt() and 0x80) != 0) {
                    val w = bb.getInt(4)
                    val ht = bb.getInt(8)
                    if (w > 0 && ht > 0) {
                        vw = w
                        vh = ht
                        p("[akis] video boyutu: ${vw}x${vh}")
                        notifyDisplaySize(vw, vh)
                    }
                    val shCopy = h.clone()
                    synchronized(previewCacheLock) {
                        latestSessionHdr = shCopy
                    }
                    if (previewActive) {
                        previewQueue.offer(shCopy)
                    }
                    out.write(h)
                    out.flush()
                    total += 12
                } else {
                    val flags = bb.getLong(0)
                    val size = bb.getInt(8)
                    if (size < 0 || size > 8_000_000) throw IOException("gecersiz paket boyutu: $size")
                    if (size > payload.size) payload = ByteArray(size)
                    inp.readFully(payload, 0, size)
                    out.write(h)
                    out.write(payload, 0, size)
                    out.flush()
                    total += 12 + size

                    val isConfig = (flags and (1L shl 62)) != 0L
                    val isKey = (flags and (1L shl 61)) != 0L
                    if (isConfig || isKey || previewActive) {
                        val pkt = ByteArray(12 + size)
                        System.arraycopy(h, 0, pkt, 0, 12)
                        System.arraycopy(payload, 0, pkt, 12, size)
                        if (isConfig || isKey) {
                            synchronized(previewCacheLock) {
                                if (isConfig) latestConfigPacket = pkt
                                if (isKey) latestKeyPacket = pkt
                            }
                        }
                        if (previewActive) {
                            if (!previewQueue.offer(pkt)) {
                                if (isConfig || isKey) {
                                    previewQueue.clear()
                                    previewQueue.offer(pkt)
                                } else {
                                    previewQueue.poll()
                                    previewQueue.offer(pkt)
                                }
                            }
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - last > 5000) {
                    p("[akis] toplam ${total / 1024} KB (${vw}x${vh})")
                    last = now
                }
            }
        } catch (e: Throwable) {
            p("SONLANDI: $e")
        } finally {
            beacon = false
            previewActive = false
            try { sock?.close() } catch (_: Exception) {}
            try { audSock?.close() } catch (_: Exception) {}
            try { serverProc?.destroy() } catch (_: Exception) {}
        }
        p("BITTI")
        System.exit(0)
    }
}
