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
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
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
    private const val TMP_JAR_PATH = "/data/local/tmp/m2t-scrcpy-server.jar"

    @Volatile
    private var displayId = -1

    @Volatile
    private var pendingLaunchTarget: String? = null

    private val launchedOnVirtualDisplay = ConcurrentHashMap.newKeySet<String>()
    private val launchLock = Any()

    @Volatile
    private var serverProc: Process? = null

    @Volatile
    private var beacon = true

    @Volatile
    private var allowedTvIp: String? = null

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

    @Volatile
    private var tvSocket: Socket? = null

    @Volatile
    private var tvOut: OutputStream? = null
    private val tvWriteLock = Any()

    @Volatile
    private var previewActive = false
    private val previewQueue = LinkedBlockingQueue<ByteArray>(24)
    private val previewCacheLock = Any()

    @Volatile
    private var initialHdr69: ByteArray? = null

    @Volatile
    private var latestSessionHdr: ByteArray? = null

    @Volatile
    private var latestConfigPacket: ByteArray? = null

    @Volatile
    private var latestKeyPacket: ByteArray? = null

    private val localClients = CopyOnWriteArrayList<OutputStream>()

    private fun stopPreviousRelayIfRunning() {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", LOCAL_CTL_PORT), 200)
                s.getOutputStream().write("QUIT\n".toByteArray())
                s.getOutputStream().flush()
            }
            Thread.sleep(350)
        } catch (_: Exception) {
        }
    }

    private fun shutdownAndExit() {
        beacon = false
        previewActive = false
        try { tvSocket?.close() } catch (_: Throwable) {}
        try { serverProc?.destroy() } catch (_: Throwable) {}
        try { File(TMP_JAR_PATH).delete() } catch (_: Throwable) {}
        System.exit(0)
    }

    private fun startTvVideoServer(port: Int) {
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                while (true) {
                    val tv = ss.accept()
                    beacon = false
                    allowedTvIp = tv.inetAddress.hostAddress
                    tv.tcpNoDelay = true
                    tv.sendBufferSize = 256 * 1024
                    p("TV baglandi: ${tv.inetAddress.hostAddress}")
                    try { tvSocket?.close() } catch (_: Exception) {}
                    tvSocket = tv
                    val out = BufferedOutputStream(tv.getOutputStream(), 65536)
                    synchronized(tvWriteLock) {
                        try {
                            synchronized(previewCacheLock) {
                                initialHdr69?.let { out.write(it) }
                                latestSessionHdr?.let { out.write(it) }
                                latestConfigPacket?.let { out.write(it) }
                                latestKeyPacket?.let { out.write(it) }
                            }
                            out.flush()
                            tvOut = out
                        } catch (e: Exception) {
                            tvOut = null
                            beacon = true
                            try { tv.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                p("TV sunucu hatasi (port $port): $e")
            }
        }
    }

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
                    val tvIp = allowedTvIp
                    if (tvIp != null && c.inetAddress.hostAddress != tvIp) {
                        try { c.close() } catch (_: Exception) {}
                        continue
                    }
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
                    val tvIp = allowedTvIp
                    if (tvIp != null && c.inetAddress.hostAddress != tvIp) {
                        try { c.close() } catch (_: Exception) {}
                        continue
                    }
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
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_VIDEO_PORT), 2)
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

    private fun detectScrcpyDisplayIdFallback(): Int {
        return try {
            val out = runCmd("/system/bin/dumpsys", "display")
            val m = Regex("Display Id=(\\d+)[^\\n]*scrcpy", RegexOption.IGNORE_CASE).find(out)
                ?: Regex("mDisplayId=(\\d+)[\\s\\S]{0,300}?scrcpy", RegexOption.IGNORE_CASE).find(out)
            val id = m?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (id > 0) id else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun launchApp(target: String) {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) return

        if (displayId <= 0) {
            if (serverProc != null) {
                val waitEnd = System.currentTimeMillis() + 4_000
                while (displayId <= 0 && System.currentTimeMillis() < waitEnd) {
                    Thread.sleep(80)
                }
                if (displayId <= 0) {
                    val detected = detectScrcpyDisplayIdFallback()
                    if (detected > 0) displayId = detected
                }
            }
            if (displayId <= 0) {
                pendingLaunchTarget = cleanTarget
                p("[beklemede] Sanal ekran hazirlaniyor, '$cleanTarget' siraya alindi.")
                return
            }
        }

        synchronized(launchLock) {
            val targetDisp = displayId
            if (targetDisp <= 0) {
                pendingLaunchTarget = cleanTarget
                return
            }

            var comp = cleanTarget
            if (!comp.contains('/')) {
                try {
                    val r = runCmd(
                        "/system/bin/cmd", "package", "resolve-activity", "--brief",
                        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", cleanTarget
                    )
                    val last = r.lines().map { it.trim() }.lastOrNull { it.contains('/') && !it.contains(' ') }
                    if (last != null) comp = last
                } catch (e: Exception) {
                    p("cozum hatasi: $e")
                }
            }

            val pkg = comp.substringBefore('/').trim()
            val firstLaunchOnTv = pkg.isNotEmpty() && !launchedOnVirtualDisplay.contains(pkg)

            p("Uygulama sanal ekranda (id=$targetDisp) baslatiliyor: $comp")

            val cmd = mutableListOf(
                "/system/bin/am", "start",
                "--display", targetDisp.toString(),
                "--windowingMode", "1",
                "--activity-new-task"
            )
            if (firstLaunchOnTv) {
                cmd.add("--activity-clear-task")
            }
            cmd.addAll(listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"))
            if (comp.contains('/')) {
                cmd.add("-n")
                cmd.add(comp)
            } else {
                cmd.add("-p")
                cmd.add(comp)
            }

            val out = runCmd(*cmd.toTypedArray())
            out.lines().filter { it.isNotBlank() }.forEach { p("[am] $it") }

            if (pkg.isNotEmpty()) {
                launchedOnVirtualDisplay.add(pkg)
            }

            // Telefon ekraninda (Display 0) KumandaActivity'nin her zaman en ustte kalmasini sagla
            try {
                runCmd(
                    "/system/bin/am", "start", "--display", "0",
                    "--activity-single-top", "--activity-reorder-to-front",
                    "-n", "com.hk.mobile2tvgame/.KumandaActivity"
                )
            } catch (_: Exception) {}
        }
    }

    private fun onVirtualDisplayReady(newId: Int) {
        if (newId <= 0) return
        val wasUnset = displayId <= 0
        displayId = newId
        if (wasUnset) {
            p("Sanal ekran hazir: id=$newId")
            val target = pendingLaunchTarget
            if (!target.isNullOrBlank()) {
                pendingLaunchTarget = null
                thread(isDaemon = true) {
                    try {
                        launchApp(target)
                    } catch (e: Exception) {
                        p("am hatasi: $e")
                    }
                }
            }
        }
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
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_CTL_PORT), 4)
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
                                    val trimmed = l.trim()
                                    if (trimmed == "QUIT") {
                                        p("Durdurma komutu alindi, cikiliyor...")
                                        shutdownAndExit()
                                        return@forEachLine
                                    }
                                    val t = trimmed.split(' ')
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
            while (true) {
                if (beacon) {
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
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
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
                Thread.sleep(80)
            }
        }
        return null
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Runtime.getRuntime().addShutdownHook(Thread {
            try { serverProc?.destroy() } catch (_: Throwable) {}
            try { File(TMP_JAR_PATH).delete() } catch (_: Throwable) {}
        })
        var sock: LocalSocket? = null
        var audSock: LocalSocket? = null
        try {
            val apk = args[0]
            val version = args[1]
            val startApp = args[2].trim()
            val display = args[3]
            val port = args[4].toInt()
            if (startApp.isNotEmpty()) {
                pendingLaunchTarget = startApp
            }
            Regex("(\\d+)x(\\d+)").find(display)?.let {
                vw = it.groupValues[1].toInt()
                vh = it.groupValues[2].toInt()
            }

            // Eger onceki oturumdan acik kalmis bir RelayMain varsa temizce kapat
            stopPreviousRelayIfRunning()

            startLocalControl()
            startCursorServer()
            startAudioServer()
            startLocalVideoServer()

            val dst = TMP_JAR_PATH
            ZipFile(apk).use { z ->
                val e = z.getEntry("assets/scrcpy-server.jar")
                    ?: throw IOException("apk icinde assets/scrcpy-server.jar yok")
                z.getInputStream(e).use { i -> File(dst).outputStream().use { o -> i.copyTo(o) } }
            }

            val maxDim = maxOf(vw, vh)
            val bitRate = when {
                maxDim <= 1366 -> 4_000_000
                maxDim <= 1680 -> 6_500_000
                else -> 10_000_000
            }
            val maxFps = when {
                maxDim <= 1366 -> 45
                maxDim <= 1680 -> 50
                else -> 60
            }

            p("Sanal ekran baslatiliyor ($display)...")
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
                        if (m != null) {
                            val parsedId = m.groupValues[1].toIntOrNull() ?: -1
                            if (parsedId > 0) {
                                onVirtualDisplayReady(parsedId)
                            }
                        }
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

            thread(isDaemon = true) {
                val waitEnd = System.currentTimeMillis() + 8_000
                while (displayId <= 0 && System.currentTimeMillis() < waitEnd) {
                    Thread.sleep(150)
                    val fb = detectScrcpyDisplayIdFallback()
                    if (fb > 0) {
                        onVirtualDisplayReady(fb)
                        break
                    }
                }
            }

            val inp = DataInputStream(BufferedInputStream(sock.inputStream, 65536))
            val hdr = ByteArray(69)
            inp.readFully(hdr)
            synchronized(previewCacheLock) {
                initialHdr69 = hdr.clone()
            }

            // Sanal ekran ve yerel onizleme artik hazir; simdi TV sunucusunu ve UDP beacon'i baslat
            startTvVideoServer(port)
            startBeacon(port)
            p("Sanal ekran aktif! TV bekleniyor (port $port)...")

            val h = ByteArray(12)
            var payload = ByteArray(256 * 1024)
            var total = 69L
            var last = System.currentTimeMillis()
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
                    val currentTv = tvOut
                    if (currentTv != null) {
                        synchronized(tvWriteLock) {
                            try {
                                currentTv.write(h)
                                currentTv.flush()
                            } catch (_: Exception) {
                                tvOut = null
                                beacon = true
                                p("TV baglantisi kesildi, TV bekleniyor...")
                            }
                        }
                    }
                    total += 12
                } else {
                    val flags = bb.getLong(0)
                    val size = bb.getInt(8)
                    if (size < 0 || size > 8_000_000) throw IOException("gecersiz paket boyutu: $size")
                    if (size > payload.size) payload = ByteArray(size)
                    inp.readFully(payload, 0, size)
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

                    val currentTv = tvOut
                    if (currentTv != null) {
                        synchronized(tvWriteLock) {
                            try {
                                currentTv.write(h)
                                currentTv.write(payload, 0, size)
                                currentTv.flush()
                            } catch (_: Exception) {
                                tvOut = null
                                beacon = true
                                p("TV baglantisi kesildi, TV bekleniyor...")
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
            try { tvSocket?.close() } catch (_: Exception) {}
            try { sock?.close() } catch (_: Exception) {}
            try { audSock?.close() } catch (_: Exception) {}
            try { serverProc?.destroy() } catch (_: Exception) {}
            try { File(TMP_JAR_PATH).delete() } catch (_: Exception) {}
        }
        p("BITTI")
        System.exit(0)
    }
}