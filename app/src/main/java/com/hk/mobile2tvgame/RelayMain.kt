package com.hk.mobile2tvgame

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.random.Random

object RelayMain {

    private const val BEACON_PORT = 27184
    private const val LOCAL_CTL_PORT = 27185
    private const val CURSOR_PORT = 27186
    private const val AUDIO_PORT = 27187
    private const val LOCAL_VIDEO_PORT = 27188
    private const val WEB_HTTP_PORT = 8080
    private const val TMP_JAR_PATH = "/data/local/tmp/m2t-scrcpy-server.jar"

    // TV'ye giden akis: paket turleri ve gecikme sinirlari
    private const val KIND_P = 0      // normal (P) kare: kuyruk dolarsa atilir
    private const val KIND_KEY = 1    // keyframe (IDR): her zaman gonderilir
    private const val KIND_CFG = 2    // oturum basligi / SPS-PPS: her zaman gonderilir

    // Uygulama kuyrugunda bekleyen canli video bu degeri asarsa, sonraki keyframe'e kadar kare atilir.
    // 128 KB ~ 10 Mbps'te yaklasik 100 ms. Donma/gecikme dengesini buradan ayarlayabilirsiniz.
    private const val TV_MAX_QUEUED_BYTES = 128 * 1024

    // Keyframe geldiginde kuyrukta bundan fazla bekleyen P-kare varsa hepsi silinir (aninda gecikme sifirlanir).
    private const val TV_TRIM_BYTES = 64 * 1024

    // Linux bu degeri 2 katina cikarir; eski 256 KB (=512 KB, ~400 ms gecikme) cok buyuktu.
    private const val TV_SEND_BUFFER = 64 * 1024

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
    private var lastCurX = 0.5

    @Volatile
    private var lastCurY = 0.5

    @Volatile
    private var lastCurAction = 3

    @Volatile
    private var audOut: OutputStream? = null
    private val audLock = Any()

    @Volatile
    private var tvSocket: Socket? = null

    @Volatile
    private var tvSender: TvSender? = null

    // Her onizleme istemcisi (📺 Ekran ve Web Decoder) icin AYRI kuyruk:
    // boylece istemciler birbirinin H.264 paketlerini calmaz ve goruntu asla bozulmaz!
    private val previewClientQueues = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()
    private val previewCacheLock = Any()

    @Volatile
    private var initialHdr69: ByteArray? = null

    @Volatile
    private var latestSessionHdr: ByteArray? = null

    @Volatile
    private var latestConfigPacket: ByteArray? = null

    // Son IDR Keyframe + o Keyframe'den sonraki P-frame'lerin tam listesi (GOP):
    // Yeni baglanan istemciye GOP'un tamami gonderilir ki pikseller asla birbirine girmesin!
    private val gopPackets = ArrayList<ByteArray>(90)

    private val localClients = CopyOnWriteArrayList<OutputStream>()

    @Volatile
    private var webClientCount = 0

    @Volatile
    private var latestJpegFrame: ByteArray? = null
    private val jpegNotifyLock = Object()

    // H.264 Annex-B paket iceriginden SPS/PPS (tip 7/8) veya IDR Keyframe (tip 5) tespiti:
    private fun inspectH264Payload(buf: ByteArray, size: Int): Pair<Boolean, Boolean> {
        var hasConfig = false
        var hasIdr = false
        val limit = minOf(size - 4, 256)
        var i = 0
        while (i < limit) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                val nalIdx = when {
                    buf[i + 2].toInt() == 1 -> i + 3
                    buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1 -> i + 4
                    else -> -1
                }
                if (nalIdx in 0 until size) {
                    val nalType = buf[nalIdx].toInt() and 0x1F
                    if (nalType == 7 || nalType == 8) hasConfig = true
                    if (nalType == 5) {
                        hasIdr = true
                        break
                    }
                    i = nalIdx
                    continue
                }
            }
            i++
        }
        return Pair(hasConfig, hasIdr)
    }

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
        previewClientQueues.clear()
        try { tvSocket?.close() } catch (_: Throwable) {}
        try { serverProc?.destroy() } catch (_: Throwable) {}
        try { File(TMP_JAR_PATH).delete() } catch (_: Throwable) {}
        System.exit(0)
    }

    /**
     * TV'ye giden video icin AYRI yazma kuyrugu ve iş parçacığı.
     *
     * Eskiden scrcpy'yi okuyan dongu dogrudan TV soketine yaziyordu. Wi-Fi bir an takilinca
     * yazma bloklaniyor, scrcpy okunamiyor, sonra kareler birikmis halde hizla akiyordu:
     * donma + ardindan "hizlandirilmis" goruntu + kalici gecikme.
     *
     * Simdi okuma dongusu hic beklemez (offer() hemen doner). TV yetismezse kuyruk dolar,
     * yeni P-kareler bir sonraki keyframe'e kadar atilir. Keyframe gelince kuyrukta kalan
     * eski P-kareler de silinir; goruntu temiz bicimde guncele atlar.
     */
    private class TvSender(private val sock: Socket, private val out: OutputStream) {
        private class Item(val bytes: ByteArray, val kind: Int, val counted: Boolean)

        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()
        private val q = java.util.ArrayDeque<Item>()
        private var queuedBytes = 0   // sadece canli (counted) paketler
        private var waitKey = false
        private var waitKeySince = 0L
        private var closed = false
        private var dropped = 0L

        fun start() {
            thread(isDaemon = true, name = "tv-sender") { loop() }
        }

        /** Canli akistan gelen paket. Asla bloklamaz. */
        fun offer(pkt: ByteArray, kind: Int) = add(pkt, kind, true)

        /** Yeni baglanan TV'ye onceki GOP'u tekrar gonderirken kullanilir; kuyruk limitine sayilmaz. */
        fun offerReplay(pkt: ByteArray, kind: Int) = add(pkt, kind, false)

        fun queuedKb(): Int = lock.withLock { queuedBytes / 1024 }

        fun droppedCount(): Long = lock.withLock { dropped }

        fun close() {
            lock.withLock {
                closed = true
                q.clear()
                queuedBytes = 0
                notEmpty.signalAll()
            }
            try { sock.close() } catch (_: Exception) {}
        }

        private fun add(pkt: ByteArray, kind: Int, counted: Boolean) {
            lock.withLock {
                if (closed) return
                if (counted) {
                    if (kind == KIND_P) {
                        if (waitKey) {
                            // Guvenlik agi: keyframe 2 sn icinde gelmezse akisi dondurma, kareleri yine ilet.
                            if (System.currentTimeMillis() - waitKeySince > 2000) {
                                waitKey = false
                            } else {
                                dropped++
                                return
                            }
                        }
                        if (queuedBytes > TV_MAX_QUEUED_BYTES) {
                            waitKey = true
                            waitKeySince = System.currentTimeMillis()
                            dropped++
                            RelayMain.p("[tv] kuyruk doldu (${queuedBytes / 1024} KB): keyframe'e kadar kare atiliyor")
                            return
                        }
                    } else if (kind == KIND_KEY) {
                        waitKey = false
                        if (queuedBytes > TV_TRIM_BYTES) trimQueuedPFrames()
                    }
                }
                q.addLast(Item(pkt, kind, counted))
                if (counted) queuedBytes += pkt.size
                notEmpty.signal()
            }
        }

        // Cagiran lock'u tutuyor. Yeni keyframe geldigi icin eski P-kareler artik gereksiz.
        private fun trimQueuedPFrames() {
            val iter = q.iterator()
            while (iter.hasNext()) {
                val item = iter.next()
                if (item.kind == KIND_P) {
                    iter.remove()
                    if (item.counted) queuedBytes -= item.bytes.size
                    dropped++
                }
            }
        }

        private fun loop() {
            try {
                while (true) {
                    val item = lock.withLock {
                        while (q.isEmpty() && !closed) notEmpty.await()
                        if (closed) null else q.removeFirst()
                    } ?: break
                    out.write(item.bytes)
                    out.flush()
                    if (item.counted) lock.withLock { queuedBytes -= item.bytes.size }
                }
            } catch (_: Exception) {
            } finally {
                val wasActive = lock.withLock {
                    val a = !closed
                    closed = true
                    q.clear()
                    queuedBytes = 0
                    a
                }
                try { sock.close() } catch (_: Exception) {}
                // Yerine yeni bir TV gectiyse (close() ile kapatildiysa) bayrak/isaretleri bozma.
                if (wasActive && RelayMain.tvSender === this) {
                    RelayMain.tvSender = null
                    RelayMain.beacon = true
                    RelayMain.p("TV baglantisi kesildi, TV bekleniyor...")
                }
            }
        }
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
                    tv.sendBufferSize = TV_SEND_BUFFER
                    p("TV baglandi: ${tv.inetAddress.hostAddress}")
                    // Once eski gondericiyi kapat ki kendi kapanisi 'TV koptu' sanilmasin.
                    tvSender?.close()
                    try { tvSocket?.close() } catch (_: Exception) {}
                    tvSocket = tv
                    val sender = TvSender(tv, BufferedOutputStream(tv.getOutputStream(), 65536))
                    // Onbellekteki GOP'u kuyruga koy ve kaydi AYNI kilit altinda yap:
                    // ana dongu de ayni kilitle ekledigi icin hicbir paket kacmaz ya da cift gitmez.
                    synchronized(previewCacheLock) {
                        initialHdr69?.let { sender.offerReplay(it, KIND_CFG) }
                        latestSessionHdr?.let { sender.offerReplay(it, KIND_CFG) }
                        latestConfigPacket?.let { sender.offerReplay(it, KIND_CFG) }
                        gopPackets.forEachIndexed { idx, pkt ->
                            sender.offerReplay(pkt, if (idx == 0) KIND_KEY else KIND_P)
                        }
                        tvSender = sender
                    }
                    sender.start()
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
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_VIDEO_PORT), 6)
                while (true) {
                    val c = ss.accept()
                    thread(isDaemon = true) {
                        val clientQueue = LinkedBlockingQueue<ByteArray>(90)
                        try {
                            c.tcpNoDelay = true
                            c.sendBufferSize = 256 * 1024
                            val out = BufferedOutputStream(c.getOutputStream(), 65536)
                            synchronized(previewCacheLock) {
                                val sh = latestSessionHdr ?: ByteBuffer.allocate(12).apply {
                                    put(0, 0x80.toByte())
                                    putInt(4, vw)
                                    putInt(8, vh)
                                }.array()
                                out.write(sh)
                                latestConfigPacket?.let { out.write(it) }
                                for (pkt in gopPackets) {
                                    out.write(pkt)
                                }
                                out.flush()
                                previewClientQueues.add(clientQueue)
                            }
                            while (!c.isClosed) {
                                val pkt = clientQueue.take()
                                out.write(pkt)
                                while (true) {
                                    val next = clientQueue.poll() ?: break
                                    out.write(next)
                                }
                                out.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            previewClientQueues.remove(clientQueue)
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
        lastCurX = nx.coerceIn(0.0, 1.0)
        lastCurY = ny.coerceIn(0.0, 1.0)
        lastCurAction = action
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

            // Yeni bir uygulama veya oyun acildiginda, daha once acilmis olan diger uygulama/oyunlari
            // arkaplanda tamamen kapat (force-stop) ki cihazi yavaslatmasin:
            if (pkg.isNotEmpty()) {
                val prevPackages = launchedOnVirtualDisplay.filter {
                    it != pkg && it != "com.hk.mobile2tvgame" && it != "moe.shizuku.privileged.api"
                }
                for (prevPkg in prevPackages) {
                    try {
                        p("Onceki uygulama arkaplanda kapatiliyor: $prevPkg")
                        runCmd("/system/bin/am", "force-stop", prevPkg)
                        launchedOnVirtualDisplay.remove(prevPkg)
                    } catch (_: Exception) {}
                }
            }

            if (firstLaunchOnTv && pkg != "com.hk.mobile2tvgame" && pkg != "moe.shizuku.privileged.api") {
                try {
                    runCmd("/system/bin/am", "force-stop", pkg)
                } catch (_: Exception) {}
            }

            p("Uygulama sanal ekranda (id=$targetDisp) baslatiliyor: $comp")

            val cmd = mutableListOf(
                "/system/bin/am", "start", "--display", targetDisp.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"
            )
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

    private fun findLocalWifiIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    val host = addr?.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && host.contains('.') && !host.startsWith("169.254.")) {
                        return host
                    }
                }
            }
        } catch (_: Exception) {}
        return "telefon-ip"
    }

    private fun imageToNv21(image: Image, width: Int, height: Int, outBuf: ByteArray): ByteArray {
        val needed = width * height * 3 / 2
        val nv21 = if (outBuf.size >= needed) outBuf else ByteArray(needed)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0

        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.get(nv21, 0, width * height)
            pos = width * height
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until height) {
                yBuf.position(r * yRowStride)
                val toRead = minOf(yRowStride, yBuf.remaining())
                yBuf.get(row, 0, toRead)
                if (yPixelStride == 1) {
                    System.arraycopy(row, 0, nv21, pos, width)
                    pos += width
                } else {
                    for (c in 0 until width) {
                        nv21[pos++] = row[c * yPixelStride]
                    }
                }
            }
        }

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2
        val vLimit = vBuf.limit()
        val uLimit = uBuf.limit()

        // Her satir tek seferde diziye kopyalanir (ByteBuffer.get(index) ile piksel piksel okumaktan cok daha hizli):
        val vNeeded = (uvWidth - 1) * vPixelStride + 1
        val uNeeded = (uvWidth - 1) * uPixelStride + 1
        val vTmp = ByteArray(maxOf(vNeeded, 1))
        val uTmp = ByteArray(maxOf(uNeeded, 1))

        for (r in 0 until uvHeight) {
            val vRowStart = r * vRowStride
            val uRowStart = r * uRowStride
            val vAvail = (vLimit - vRowStart).coerceIn(0, vNeeded)
            val uAvail = (uLimit - uRowStart).coerceIn(0, uNeeded)
            if (vAvail > 0) {
                vBuf.position(vRowStart)
                vBuf.get(vTmp, 0, vAvail)
            }
            if (uAvail > 0) {
                uBuf.position(uRowStart)
                uBuf.get(uTmp, 0, uAvail)
            }
            for (c in 0 until uvWidth) {
                val vi = c * vPixelStride
                val ui = c * uPixelStride
                nv21[pos++] = if (vi < vAvail) vTmp[vi] else 128.toByte()
                nv21[pos++] = if (ui < uAvail) uTmp[ui] else 128.toByte()
            }
        }
        return nv21
    }

    private fun startWebDecoderLoopIfNeeded() {
        thread(isDaemon = true) {
            val h = ByteArray(12)
            val bb = ByteBuffer.wrap(h)
            var payload = ByteArray(256 * 1024)

            while (true) {
                if (webClientCount <= 0) {
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    continue
                }
                var s: Socket? = null
                var codec: MediaCodec? = null
                val decActive = java.util.concurrent.atomic.AtomicBoolean(true)
                var outThread: Thread? = null

                fun startDecoder(w: Int, ht: Int) {
                    decActive.set(false)
                    try { outThread?.join(250) } catch (_: Exception) {}
                    try { codec?.stop() } catch (_: Exception) {}
                    try { codec?.release() } catch (_: Exception) {}

                    val c = MediaCodec.createDecoderByType("video/avc")
                    val fmt = MediaFormat.createVideoFormat("video/avc", w, ht)
                    fmt.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                    c.configure(fmt, null, null, 0)
                    c.start()
                    codec = c
                    decActive.set(true)

                    // AYRI CIKTI THREAD'I: MediaCodec asenkron calistigi icin son kare dahil
                    // her cozulen kareyi aninda JPEG'e cevirir ve donmayi %100 engeller!
                    outThread = thread(isDaemon = true) {
                        val info = MediaCodec.BufferInfo()
                        var nv21Buf = ByteArray(w * ht * 3 / 2)
                        val bos = ByteArrayOutputStream(98304)
                        var lastJpegMs = 0L
                        while (decActive.get() && webClientCount > 0) {
                            try {
                                val outIdx = c.dequeueOutputBuffer(info, 15_000)
                                if (outIdx >= 0) {
                                    val now = System.currentTimeMillis()
                                    val encodeNow = (info.size > 0) && ((now - lastJpegMs) >= 33L || latestJpegFrame == null)
                                    if (encodeNow) {
                                        val img = c.getOutputImage(outIdx)
                                        if (img != null) {
                                            try {
                                                val iw = img.width
                                                val ih = img.height
                                                nv21Buf = imageToNv21(img, iw, ih, nv21Buf)
                                                bos.reset()
                                                YuvImage(nv21Buf, ImageFormat.NV21, iw, ih, null)
                                                    .compressToJpeg(Rect(0, 0, iw, ih), 65, bos)
                                                latestJpegFrame = bos.toByteArray()
                                                lastJpegMs = now
                                                synchronized(jpegNotifyLock) {
                                                    jpegNotifyLock.notifyAll()
                                                }
                                            } finally {
                                                img.close()
                                            }
                                        }
                                    }
                                    c.releaseOutputBuffer(outIdx, false)
                                }
                            } catch (_: Exception) {
                                if (!decActive.get()) break
                            }
                        }
                    }
                }

                try {
                    s = Socket("127.0.0.1", LOCAL_VIDEO_PORT).also {
                        it.tcpNoDelay = true
                        it.receiveBufferSize = 256 * 1024
                    }
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
                    var curW = vw
                    var curH = vh
                    // Karar dusurulursa (decoder mesgulse) sonraki P-kareler bozuk gorunur; bu yuzden
                    // bir sonraki IDR (anahtar kare) gelene kadar P-kareleri hic beslemiyoruz:
                    var needKey = true
                    var needKeySince = System.currentTimeMillis()

                    while (webClientCount > 0) {
                        inp.readFully(h)
                        if ((h[0].toInt() and 0x80) != 0) {
                            val w = bb.getInt(4)
                            val ht = bb.getInt(8)
                            if (w > 0 && ht > 0) {
                                curW = w
                                curH = ht
                                startDecoder(curW, curH)
                                needKey = true
                                needKeySince = System.currentTimeMillis()
                            }
                        } else {
                            val flags = bb.getLong(0)
                            val size = bb.getInt(8)
                            if (size < 0 || size > 8_000_000) break
                            if (size > payload.size) payload = ByteArray(size)
                            inp.readFully(payload, 0, size)
                            if (codec == null) {
                                startDecoder(curW, curH)
                                needKey = true
                                needKeySince = System.currentTimeMillis()
                            }
                            val (nalCfg, nalIdr) = inspectH264Payload(payload, size)
                            val isConfig = nalCfg || ((flags and (1L shl 62)) != 0L) || ((flags and (1L shl 63)) != 0L)
                            val isKey = !isConfig && (nalIdr || ((flags and (1L shl 61)) != 0L))
                            val pts = flags and ((1L shl 61) - 1)
                            val c = codec ?: continue

                            if (isKey) needKey = false
                            // Guvenlik: anahtar kare 2 sn icinde taninmazsa eski davranisa don (goruntu donmasin)
                            if (needKey && !isConfig && (System.currentTimeMillis() - needKeySince) > 2000L) {
                                needKey = false
                            }
                            // Anahtar kare bekleniyorsa P-kareleri atla (bozuk piksel yerine son saglam kare kalir)
                            if (needKey && !isConfig && !isKey) continue

                            // Giris tamponu bulunamazsa birkac kez tekrar dene; yine olmazsa
                            // sonraki IDR'ye kadar P-kareleri atla:
                            var inIdx = -1
                            var tries = 0
                            while (tries < 3 && inIdx < 0) {
                                inIdx = c.dequeueInputBuffer(50_000)
                                tries++
                            }
                            if (inIdx >= 0) {
                                val inBuf = c.getInputBuffer(inIdx)
                                if (inBuf != null) {
                                    inBuf.clear()
                                    inBuf.put(payload, 0, size)
                                    c.queueInputBuffer(
                                        inIdx, 0, size, pts,
                                        if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                                    )
                                }
                            } else if (!isConfig) {
                                needKey = true
                                needKeySince = System.currentTimeMillis()
                            }
                        }
                    }
                } catch (_: Exception) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) {}
                } finally {
                    decActive.set(false)
                    try { s?.close() } catch (_: Exception) {}
                    try { outThread?.join(250) } catch (_: Exception) {}
                    try { codec?.stop() } catch (_: Exception) {}
                    try { codec?.release() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startWebHttpServer() {
        startWebDecoderLoopIfNeeded()
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(WEB_HTTP_PORT))
                val ip = findLocalWifiIp()
                p("Web Yayini: http://$ip:$WEB_HTTP_PORT")
                while (true) {
                    val c = ss.accept()
                    thread(isDaemon = true) {
                        try {
                            c.tcpNoDelay = true
                            c.soTimeout = 10_000
                            val pbIn = PushbackInputStream(c.getInputStream(), 4)
                            val firstByte = pbIn.read()
                            if (firstByte < 0 || firstByte == 0x16) {
                                try { c.close() } catch (_: Exception) {}
                                return@thread
                            }
                            pbIn.unread(firstByte)

                            val reader = pbIn.bufferedReader(Charsets.UTF_8)
                            val reqLine = reader.readLine() ?: ""
                            while (true) {
                                val hLine = reader.readLine() ?: break
                                if (hLine.isEmpty()) break
                            }

                            val out = BufferedOutputStream(c.getOutputStream(), 65536)
                            when {
                                reqLine.contains("GET /stream.mjpg") -> {
                                    c.soTimeout = 0
                                    webClientCount++
                                    try {
                                        val hdr = "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: multipart/x-mixed-replace; boundary=m2tframe\r\n" +
                                                "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                                                "Pragma: no-cache\r\n" +
                                                "Connection: close\r\n\r\n"
                                        out.write(hdr.toByteArray(Charsets.UTF_8))
                                        out.flush()
                                        var lastSent: ByteArray? = null
                                        var lastSentTime = 0L
                                        while (!c.isClosed) {
                                            var jpg = latestJpegFrame
                                            val now = System.currentTimeMillis()
                                            // Ekran sabit dursa bile tarayici baglantisini canli tutmak icin 400ms'de bir son kareyi yenile:
                                            if (jpg == null || (jpg === lastSent && (now - lastSentTime) < 400L)) {
                                                synchronized(jpegNotifyLock) {
                                                    try { jpegNotifyLock.wait(200) } catch (_: InterruptedException) {}
                                                }
                                                jpg = latestJpegFrame
                                            }
                                            val now2 = System.currentTimeMillis()
                                            if (jpg != null && (jpg !== lastSent || (now2 - lastSentTime) >= 400L)) {
                                                lastSent = jpg
                                                lastSentTime = now2
                                                val partHdr = "--m2tframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\n\r\n"
                                                out.write(partHdr.toByteArray(Charsets.UTF_8))
                                                out.write(jpg)
                                                out.write("\r\n".toByteArray(Charsets.UTF_8))
                                                out.flush()
                                            }
                                        }
                                    } finally {
                                        webClientCount = (webClientCount - 1).coerceAtLeast(0)
                                    }
                                }
                                reqLine.contains("GET /cursor") -> {
                                    val body = "{\"x\":$lastCurX,\"y\":$lastCurY,\"a\":$lastCurAction}".toByteArray(Charsets.UTF_8)
                                    val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                                    out.write(hdr.toByteArray(Charsets.UTF_8))
                                    out.write(body)
                                    out.flush()
                                }
                                reqLine.contains("GET /favicon.ico") -> {
                                    val hdr = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                                    out.write(hdr.toByteArray(Charsets.UTF_8))
                                    out.flush()
                                }
                                else -> {
                                    val html = """<!doctype html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mobile2TvGame Canli TV Yayini</title>
<style>
  html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;
    display:flex;align-items:center;justify-content:center;font-family:system-ui,sans-serif;color:#fff}
  #wrap{position:relative;width:100vw;height:100vh;display:flex;align-items:center;justify-content:center}
  #streamImg{width:100%;height:100%;object-fit:contain;display:block}
  #cur{position:fixed;width:22px;height:22px;margin-left:-11px;margin-top:-11px;border:3px solid #ff3b30;
    background:rgba(255,255,255,0.25);border-radius:50%;pointer-events:none;z-index:5;display:none;
    box-shadow:0 0 8px rgba(0,0,0,0.8)}
  #fsBtn{position:fixed;top:12px;right:12px;background:rgba(20,30,55,0.85);color:#fff;
    border:1px solid #4da3ff;border-radius:8px;padding:8px 14px;font-size:14px;cursor:pointer;z-index:10}
</style></head>
<body>
  <button id="fsBtn" onclick="toggleFs()">⛶ Tam Ekran</button>
  <div id="wrap">
    <img id="streamImg" src="/stream.mjpg" alt="Canli Yayin Bekleniyor...">
    <div id="cur"></div>
  </div>
  <script>
    const btn = document.getElementById('fsBtn');
    const img = document.getElementById('streamImg');
    const cur = document.getElementById('cur');
    function toggleFs(){
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(()=>{});
      } else {
        document.exitFullscreen().catch(()=>{});
      }
    }
    document.addEventListener('fullscreenchange', ()=>{
      btn.style.display = document.fullscreenElement ? 'none' : 'block';
    });
    // Baglanti koparsa otomatik yeniden baglan
    img.addEventListener('error', ()=>{
      setTimeout(()=>{ img.src = '/stream.mjpg?t=' + Date.now(); }, 400);
    });
    // Imlec konumunu guncelle
    async function pollCursor(){
      try {
        const r = await fetch('/cursor', {cache:'no-store'});
        if (r.ok) {
          const d = await r.json();
          const rect = img.getBoundingClientRect();
          const natW = img.naturalWidth || 16, natH = img.naturalHeight || 9;
          const scale = Math.min(rect.width / natW, rect.height / natH);
          const dispW = natW * scale, dispH = natH * scale;
          const offX = rect.left + (rect.width - dispW) / 2;
          const offY = rect.top + (rect.height - dispH) / 2;
          cur.style.display = 'block';
          cur.style.left = (offX + d.x * dispW) + 'px';
          cur.style.top = (offY + d.y * dispH) + 'px';
          cur.style.borderColor = (d.a === 0 || d.a === 2) ? '#2ecc71' : '#ff3b30';
        }
      } catch(e) {}
      setTimeout(pollCursor, 65);
    }
    pollCursor();
  </script>
</body></html>"""
                                    val body = html.toByteArray(Charsets.UTF_8)
                                    val hdr = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: text/html; charset=utf-8\r\n" +
                                            "Content-Length: ${body.size}\r\n" +
                                            "Cache-Control: no-store\r\n" +
                                            "Connection: close\r\n\r\n"
                                    out.write(hdr.toByteArray(Charsets.UTF_8))
                                    out.write(body)
                                    out.flush()
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { c.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                p("Web sunucu hatasi (port $WEB_HTTP_PORT): $e")
            }
        }
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
            // Istege bagli kalite ayari (ayarlardan): 0 = otomatik (cozunurluge gore)
            val qualityFps = (args.getOrNull(5)?.toIntOrNull() ?: 0).coerceIn(0, 120)
            val qualityKbps = (args.getOrNull(6)?.toIntOrNull() ?: 0).coerceIn(0, 30_000)
            if (startApp.isNotEmpty()) {
                pendingLaunchTarget = startApp
            }
            Regex("(\\d+)x(\\d+)").find(display)?.let {
                vw = it.groupValues[1].toInt()
                vh = it.groupValues[2].toInt()
            }

            stopPreviousRelayIfRunning()

            startLocalControl()
            startCursorServer()
            startAudioServer()
            startLocalVideoServer()
            startWebHttpServer()

            val dst = TMP_JAR_PATH
            ZipFile(apk).use { z ->
                val e = z.getEntry("assets/scrcpy-server.jar")
                    ?: throw IOException("apk icinde assets/scrcpy-server.jar yok")
                z.getInputStream(e).use { i -> File(dst).outputStream().use { o -> i.copyTo(o) } }
            }

            val maxDim = maxOf(vw, vh)
            val autoBitRate = when {
                maxDim <= 1366 -> 4_000_000
                maxDim <= 1680 -> 6_500_000
                else -> 10_000_000
            }
            val autoMaxFps = when {
                maxDim <= 1366 -> 45
                maxDim <= 1680 -> 50
                else -> 60
            }
            val bitRate = if (qualityKbps >= 500) qualityKbps * 1000 else autoBitRate
            val maxFps = if (qualityFps >= 15) qualityFps else autoMaxFps
            p("Kalite: ${maxFps} FPS, ${bitRate / 1000} kbps" +
                if (qualityFps >= 15 || qualityKbps >= 500) " (ayarlardan)" else " (otomatik)")

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
                        gopPackets.clear()
                        tvSender?.offer(shCopy, KIND_CFG)
                    }
                    for (q in previewClientQueues) {
                        q.offer(shCopy)
                    }
                    total += 12
                } else {
                    val flags = bb.getLong(0)
                    val size = bb.getInt(8)
                    if (size < 0 || size > 8_000_000) throw IOException("gecersiz paket boyutu: $size")
                    if (size > payload.size) payload = ByteArray(size)
                    inp.readFully(payload, 0, size)
                    total += 12 + size

                    val (nalCfg, nalIdr) = inspectH264Payload(payload, size)
                    val isConfig = nalCfg || ((flags and (1L shl 62)) != 0L)
                    val isKey = (!isConfig) && (nalIdr || ((flags and (1L shl 61)) != 0L))

                    val pkt = ByteArray(12 + size)
                    System.arraycopy(h, 0, pkt, 0, 12)
                    System.arraycopy(payload, 0, pkt, 12, size)

                    synchronized(previewCacheLock) {
                        if (isConfig) {
                            latestConfigPacket = pkt
                        } else if (isKey) {
                            gopPackets.clear()
                            gopPackets.add(pkt)
                        } else if (gopPackets.size < 90) {
                            gopPackets.add(pkt)
                        } else {
                            Unit
                        }
                        // TV'ye bloklamadan ilet (kuyruga ekle). Ayni kilit altinda: yeni baglanan TV ile yaris olmaz.
                        // IDR iceren paket (SPS/PPS ile birlikte gelse bile) keyframe sayilir.
                        tvSender?.offer(pkt, if (nalIdr) KIND_KEY else if (isConfig) KIND_CFG else if (isKey) KIND_KEY else KIND_P)
                    }

                    for (q in previewClientQueues) {
                        if (!q.offer(pkt)) {
                            if (isConfig || isKey) {
                                q.clear()
                                q.offer(pkt)
                            } else {
                                q.poll()
                                q.offer(pkt)
                            }
                        }
                    }

                }
                val now = System.currentTimeMillis()
                if (now - last > 5000) {
                    val ts = tvSender
                    p("[akis] toplam ${total / 1024} KB (${vw}x${vh}) tv-kuyruk=${ts?.queuedKb() ?: 0}KB atilan-kare=${ts?.droppedCount() ?: 0}")
                    last = now
                }
            }
        } catch (e: Throwable) {
            p("SONLANDI: $e")
        } finally {
            beacon = false
            previewClientQueues.clear()
            try { tvSender?.close() } catch (_: Exception) {}
            try { tvSocket?.close() } catch (_: Exception) {}
            try { sock?.close() } catch (_: Exception) {}
            try { audSock?.close() } catch (_: Exception) {}
            try { serverProc?.destroy() } catch (_: Exception) {}
            try { File(TMP_JAR_PATH).delete() } catch (_: Throwable) {}
        }
        p("BITTI")
        System.exit(0)
    }
}