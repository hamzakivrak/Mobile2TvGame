package com.hk.mobile2tvgame

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Shizuku uzerinden "shell" yetkisiyle app_process ile baslatilir.
 *
 * Akis:
 *  1. TCP portunu dinler, UDP duyurusu yapar (TV uygulamasi telefonu boylece bulur)
 *  2. TV baglaninca scrcpy-server'i sanal ekran modunda baslatir
 *  3. Uygulamayi/oyunu sanal ekranda acar
 *  4. scrcpy akisini (ham) TV'ye aynen iletir
 *
 * args: apkYolu surum baslatilacakPaket ekran tcpPort
 */
object RelayMain {

    private const val BEACON_PORT = 27184

    @Volatile
    private var displayId = -1

    @Volatile
    private var serverProc: Process? = null

    @Volatile
    private var beacon = true

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

    @JvmStatic
    fun main(args: Array<String>) {
        Runtime.getRuntime().addShutdownHook(Thread {
            try { serverProc?.destroy() } catch (_: Throwable) {}
        })
        var sock: LocalSocket? = null
        try {
            val apk = args[0]
            val version = args[1]
            val startApp = args[2]
            val display = args[3]
            val port = args[4].toInt()

            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            startBeacon(port)
            p("TV bekleniyor (port $port)... TV uygulamasini acin.")
            val tv = ss.accept()
            beacon = false
            tv.tcpNoDelay = true
            p("TV baglandi: ${tv.inetAddress.hostAddress}")
            val out = tv.getOutputStream()

            val dst = "/data/local/tmp/m2t-scrcpy-server.jar"
            ZipFile(apk).use { z ->
                val e = z.getEntry("assets/scrcpy-server.jar")
                    ?: throw IOException("apk icinde assets/scrcpy-server.jar yok")
                z.getInputStream(e).use { i -> File(dst).outputStream().use { o -> i.copyTo(o) } }
            }

            val scid = "%08x".format(Random.nextInt(1, 0x7fffffff))
            val cmd = listOf(
                "/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", version,
                "scid=$scid", "log_level=info",
                "audio=false", "control=false",
                "video_codec=h264",
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
                        val m = Regex("""New display:.*\(id=(\d+)\)""").find(it)
                        if (m != null) displayId = m.groupValues[1].toInt()
                    }
                } catch (_: Exception) {
                }
            }

            val deadline = System.currentTimeMillis() + 10_000
            while (sock == null && System.currentTimeMillis() < deadline) {
                try {
                    val s = LocalSocket()
                    s.connect(LocalSocketAddress("scrcpy_$scid", LocalSocketAddress.Namespace.ABSTRACT))
                    sock = s
                } catch (e: IOException) {
                    Thread.sleep(200)
                }
            }
            if (sock == null) throw IOException("sunucu soketine baglanilamadi (10 sn)")
            val input = sock.inputStream

            // Sanal ekran acilinca uygulamayi orada baslat (ayri is parcacigi)
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
                        val am = ProcessBuilder(
                            "/system/bin/am", "start", "--display", displayId.toString(),
                            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                            "-p", startApp
                        ).redirectErrorStream(true).start()
                        am.inputStream.bufferedReader().forEachLine { p("[am] $it") }
                    } catch (e: Exception) {
                        p("am hatasi: $e")
                    }
                }
            }

            // Ham akisi TV'ye aynen ilet
            val buf = ByteArray(65536)
            var total = 0L
            var last = System.currentTimeMillis()
            p("Yayin basladi.")
            while (true) {
                val n = input.read(buf)
                if (n < 0) {
                    p("Sunucu akisi bitti.")
                    break
                }
                out.write(buf, 0, n)
                out.flush()
                total += n
                val now = System.currentTimeMillis()
                if (now - last > 5000) {
                    p("[akis] toplam ${total / 1024} KB")
                    last = now
                }
            }
        } catch (e: Throwable) {
            p("SONLANDI: $e")
        } finally {
            beacon = false
            try { sock?.close() } catch (_: Exception) {}
            try { serverProc?.destroy() } catch (_: Exception) {}
        }
        p("BITTI")
        System.exit(0)
    }
}
