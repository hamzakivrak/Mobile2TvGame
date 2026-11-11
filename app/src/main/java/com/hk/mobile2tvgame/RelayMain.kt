package com.hk.mobile2tvgame

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import java.io.PushbackInputStream
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
    private const val WEB_HTTP_PORT = 8080
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

            // Eger bu uygulama daha once telefonun kendi ekraninda (Display 0) acik kaldiysa,
            // ilk kez sanal ekrana tasirken onceki Display 0 gorevini kapat ki sanal ekranda acilsin:
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

    @Volatile
    private var webClientCount = 0

    @Volatile
    private var latestJpegFrame: ByteArray? = null
    private val jpegNotifyLock = Object()

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

        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2

        for (r in 0 until uvHeight) {
            val vRowStart = r * uvRowStride
            val uRowStart = r * uPlane.rowStride
            for (c in 0 until uvWidth) {
                val vIdx = vRowStart + c * uvPixelStride
                val uIdx = uRowStart + c * uPlane.pixelStride
                nv21[pos++] = if (vIdx < vBuf.limit()) vBuf.get(vIdx) else 128.toByte()
                nv21[pos++] = if (uIdx < uBuf.limit()) uBuf.get(uIdx) else 128.toByte()
            }
        }
        return nv21
    }

    private fun startWebDecoderLoopIfNeeded() {
        thread(isDaemon = true) {
            val h = ByteArray(12)
            val bb = ByteBuffer.wrap(h)
            var payload = ByteArray(256 * 1024)
            var nv21Buf = ByteArray(1920 * 1080 * 3 / 2)
            val bos = ByteArrayOutputStream(65536)

            while (true) {
                if (webClientCount <= 0) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    continue
                }
                var s: Socket? = null
                var codec: MediaCodec? = null
                try {
                    s = Socket("127.0.0.1", LOCAL_VIDEO_PORT).also {
                        it.tcpNoDelay = true
                        it.receiveBufferSize = 128 * 1024
                    }
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 32768))
                    var decW = vw
                    var decH = vh

                    fun initCodec(w: Int, h: Int): MediaCodec {
                        try { codec?.stop() } catch (_: Exception) {}
                        try { codec?.release() } catch (_: Exception) {}
                        val c = MediaCodec.createDecoderByType("video/avc")
                        val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
                        fmt.setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        )
                        c.configure(fmt, null, null, 0)
                        c.start()
                        return c
                    }

                    val info = MediaCodec.BufferInfo()
                    var lastJpegTime = 0L

                    while (webClientCount > 0) {
                        inp.readFully(h)
                        if ((h[0].toInt() and 0x80) != 0) {
                            val w = bb.getInt(4)
                            val ht = bb.getInt(8)
                            if (w > 0 && ht > 0) {
                                decW = w
                                decH = ht
                                codec = initCodec(decW, decH)
                            }
                        } else {
                            val flags = bb.getLong(0)
                            val size = bb.getInt(8)
                            if (size < 0 || size > 8_000_000) break
                            if (size > payload.size) payload = ByteArray(size)
                            inp.readFully(payload, 0, size)
                            val config = (flags and (1L shl 62)) != 0L
                            val pts = flags and ((1L shl 61) - 1)
                            if (codec == null) {
                                codec = initCodec(decW, decH)
                            }
                            val c = codec!!
                            val inIdx = c.dequeueInputBuffer(30_000)
                            if (inIdx >= 0) {
                                val inBuf = c.getInputBuffer(inIdx)
                                if (inBuf != null) {
                                    inBuf.clear()
                                    inBuf.put(payload, 0, size)
                                    c.queueInputBuffer(
                                        inIdx, 0, size, pts,
                                        if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                                    )
                                }
                            }
                            var outIdx = c.dequeueOutputBuffer(info, 0)
                            while (outIdx >= 0) {
                                val now = System.currentTimeMillis()
                                val shouldEncodeJpeg = (now - lastJpegTime) >= 38L
                                if (shouldEncodeJpeg && info.size > 0) {
                                    try {
                                        val img = c.getOutputImage(outIdx)
                                        if (img != null) {
                                            val imgW = img.width
                                            val imgH = img.height
                                            nv21Buf = imageToNv21(img, imgW, imgH, nv21Buf)
                                            img.close()
                                            bos.reset()
                                            val yuv = YuvImage(nv21Buf, ImageFormat.NV21, imgW, imgH, null)
                                            yuv.compressToJpeg(Rect(0, 0, imgW, imgH), 62, bos)
                                            latestJpegFrame = bos.toByteArray()
                                            lastJpegTime = now
                                            synchronized(jpegNotifyLock) {
                                                jpegNotifyLock.notifyAll()
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                                c.releaseOutputBuffer(outIdx, false)
                                outIdx = c.dequeueOutputBuffer(info, 0)
                            }
                        }
                    }
                } catch (_: Exception) {
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                } finally {
                    try { s?.close() } catch (_: Exception) {}
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
                            if (firstByte < 0) {
                                try { c.close() } catch (_: Exception) {}
                                return@thread
                            }
                            // Chrome HTTPS-First Modu once TLS ClientHello (0x16) gonderir;
                            // aninda kapat ki Chrome 1ms icinde takilmadan HTTP'ye gecsin!
                            if (firstByte == 0x16) {
                                try { c.close() } catch (_: Exception) {}
                                return@thread
                            }
                            pbIn.unread(firstByte)

                            val reader = pbIn.bufferedReader(Charsets.UTF_8)
                            val reqLine = reader.readLine() ?: ""
                            // TCP RST hatasi olusmamasi icin tum HTTP istek basliklarini (bos satira kadar) oku:
                            while (true) {
                                val hLine = reader.readLine() ?: break
                                if (hLine.isEmpty()) break
                            }

                            val out = BufferedOutputStream(c.getOutputStream(), 32768)
                            if (reqLine.contains("GET /stream.mjpg")) {
                                c.soTimeout = 0
                                webClientCount++
                                try {
                                    val hdr = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: multipart/x-mixed-replace; boundary=m2tframe\r\n" +
                                            "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                                            "Pragma: no-cache\r\n" +
                                            "Connection: close\r\n\r\n"
                                    out.write(hdr.toByteArray(Charsets.UTF_8))
                                    out.flush()
                                    var lastSent: ByteArray? = null
                                    while (!c.isClosed) {
                                        var jpg = latestJpegFrame
                                        if (jpg == null || jpg === lastSent) {
                                            synchronized(jpegNotifyLock) {
                                                try { jpegNotifyLock.wait(250) } catch (_: InterruptedException) {}
                                            }
                                            jpg = latestJpegFrame
                                        }
                                        if (jpg != null && jpg !== lastSent) {
                                            lastSent = jpg
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
                            } else if (reqLine.contains("GET /favicon.ico")) {
                                val hdr = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                                out.write(hdr.toByteArray(Charsets.UTF_8))
                                out.flush()
                            } else {
                                val html = """<!doctype html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mobile2TvGame Canli TV Yayini</title>
<style>
  html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;
    display:flex;align-items:center;justify-content:center;font-family:system-ui,sans-serif;color:#fff}
  #streamImg{width:100vw;height:100vh;object-fit:contain;display:block}
  #fsBtn{position:fixed;top:12px;right:12px;background:rgba(20,30,55,0.85);color:#fff;
    border:1px solid #4da3ff;border-radius:8px;padding:8px 14px;font-size:14px;cursor:pointer;z-index:10}
</style></head>
<body>
  <button id="fsBtn" onclick="toggleFs()">⛶ Tam Ekran</button>
  <img id="streamImg" src="/stream.mjpg" alt="Canli Yayin Bekleniyor...">
  <script>
    const btn = document.getElementById('fsBtn');
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
            try { serverProc?.destroy() } catch (_: Throwable) {}
            try { File(TMP_JAR_PATH).delete() } catch (_: Throwable) {}
        }
        p("BITTI")
        System.exit(0)
    }
}