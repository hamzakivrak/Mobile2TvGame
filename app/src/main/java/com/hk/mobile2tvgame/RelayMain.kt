package com.hk.mobile2tvgame

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Shizuku uzerinden "shell" yetkisiyle, app_process ile dogrudan baslatilir
 * (Shizuku UserService mekanizmasi kullanilmaz).
 * Sonuclari standart ciktiya yazar; uygulama bunu okuyup ekrana basar.
 *
 * args: apkYolu surum baslatilacakUygulama ekran
 */
object RelayMain {

    @Volatile
    private var displayId = -1

    private fun p(s: String) {
        println(s)
        System.out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var proc: Process? = null
        var sock: LocalSocket? = null
        try {
            val apk = args[0]
            val version = args[1]
            val startApp = args[2]
            val display = args[3]

            val dst = "/data/local/tmp/m2t-scrcpy-server.jar"
            ZipFile(apk).use { z ->
                val e = z.getEntry("assets/scrcpy-server.jar")
                    ?: throw IOException("apk icinde assets/scrcpy-server.jar yok")
                z.getInputStream(e).use { i -> File(dst).outputStream().use { o -> i.copyTo(o) } }
            }
            p("jar yazildi: ${File(dst).length()} bayt")

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
            proc = pb.start()
            val pr = proc

            thread(isDaemon = true) {
                try {
                    pr.inputStream.bufferedReader().forEachLine {
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

            if (sock == null) {
                p("HATA: sunucu soketine baglanilamadi (10 sn).")
            } else {
                p("Sokete baglandi.")
                sock.soTimeout = 3000
                val input = sock.inputStream

                // scrcpy 3.x basligi: 1 dummy bayt + 64 bayt cihaz adi + 12 bayt codec bilgisi
                val head = ByteArray(77)
                var n = 0
                try {
                    while (n < head.size) {
                        val r = input.read(head, n, head.size - n)
                        if (r < 0) break
                        n += r
                    }
                } catch (e: Exception) {
                    p("Baslik okunurken: ${e.message}")
                }
                p("Baslik: $n bayt okundu")
                p("Ilk 32 bayt (hex): " + head.take(minOf(n, 32)).joinToString(" ") { "%02x".format(it) })
                if (n >= 77) {
                    val name = String(head, 1, 64, Charsets.UTF_8).trimEnd('\u0000')
                    fun int32(o: Int) = ((head[o].toInt() and 0xff) shl 24) or ((head[o + 1].toInt() and 0xff) shl 16) or
                            ((head[o + 2].toInt() and 0xff) shl 8) or (head[o + 3].toInt() and 0xff)
                    p("Cihaz adi: $name")
                    p("Codec: 0x%08x, boyut: %dx%d".format(int32(65), int32(69), int32(73)))
                }

                // Sanal ekran numarasini bekle, uygulamayi o ekranda ac
                val waitEnd = System.currentTimeMillis() + 5000
                while (displayId < 0 && System.currentTimeMillis() < waitEnd) Thread.sleep(100)
                if (displayId < 0) {
                    p("UYARI: sanal ekran numarasi sunucu gunlugunde bulunamadi.")
                } else if (startApp.isNotBlank()) {
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

                sock.soTimeout = 6000
                val buf = ByteArray(65536)
                var total = 0L
                val end = System.currentTimeMillis() + 5000
                try {
                    while (System.currentTimeMillis() < end) {
                        val r = input.read(buf)
                        if (r < 0) break
                        total += r
                    }
                } catch (_: Exception) {
                }
                p("5 sn icinde gelen video verisi: $total bayt")
            }
            Thread.sleep(500)
        } catch (e: Throwable) {
            p("ISTISNA: $e")
        } finally {
            try { sock?.close() } catch (_: Exception) {}
            try { proc?.destroy() } catch (_: Exception) {}
        }
        p("BITTI")
        System.exit(0)
    }
}
