package com.hk.mobile2tvgame

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Shizuku tarafindan "shell" yetkisiyle ayri bir surecte calistirilir.
 * 1. scrcpy-server jar'ini /data/local/tmp'ye yazar
 * 2. app_process ile sunucuyu sanal ekran modunda baslatir
 * 3. Sunucunun abstract soketine baglanip video akisinin basini okur
 */
class UserService() : IUserService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun runTest(jar: ByteArray, version: String, startApp: String, display: String): String {
        val out = StringBuilder()
        var proc: Process? = null
        var sock: LocalSocket? = null
        try {
            val dst = "/data/local/tmp/m2t-scrcpy-server.jar"
            File(dst).writeBytes(jar)
            out.append("jar yazildi: ${jar.size} bayt\n")

            val scid = "%08x".format(Random.nextInt(1, 0x7fffffff))
            val cmd = listOf(
                "/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", version,
                "scid=$scid", "log_level=info",
                "audio=false", "control=false",
                "video_codec=h264",
                "tunnel_forward=true", "send_dummy_byte=true",
                "new_display=$display",
                "start_app=$startApp"
            )
            val pb = ProcessBuilder(cmd)
            pb.environment()["CLASSPATH"] = dst
            pb.redirectErrorStream(true)
            proc = pb.start()

            val log = StringBuffer()
            thread(isDaemon = true) {
                try {
                    proc.inputStream.bufferedReader().forEachLine { log.append(it).append('\n') }
                } catch (_: Exception) {
                }
            }

            // Sunucunun soketi acmasini bekle
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
                out.append("HATA: sunucu soketine baglanilamadi (10 sn).\n")
            } else {
                out.append("Sokete baglandi.\n")
                sock.soTimeout = 3000
                val input = sock.inputStream

                // Baslik: 1 dummy bayt + 64 bayt cihaz adi + 12 bayt codec bilgisi (scrcpy 3.x protokolu)
                val head = ByteArray(77)
                var n = 0
                try {
                    while (n < head.size) {
                        val r = input.read(head, n, head.size - n)
                        if (r < 0) break
                        n += r
                    }
                } catch (e: Exception) {
                    out.append("Baslik okunurken: ${e.message}\n")
                }
                out.append("Baslik: $n bayt okundu\n")
                out.append("Ilk 32 bayt (hex): " + head.take(minOf(n, 32)).joinToString(" ") { "%02x".format(it) } + "\n")
                if (n >= 77) {
                    val name = String(head, 1, 64, Charsets.UTF_8).trimEnd('\u0000')
                    fun int32(o: Int) = ((head[o].toInt() and 0xff) shl 24) or ((head[o + 1].toInt() and 0xff) shl 16) or
                            ((head[o + 2].toInt() and 0xff) shl 8) or (head[o + 3].toInt() and 0xff)
                    out.append("Cihaz adi: $name\n")
                    out.append("Codec: 0x%08x, boyut: %dx%d\n".format(int32(65), int32(69), int32(73)))
                }

                // 3 sn boyunca gelen video baytlarini say
                val buf = ByteArray(65536)
                var total = 0L
                val end = System.currentTimeMillis() + 3000
                try {
                    while (System.currentTimeMillis() < end) {
                        val r = input.read(buf)
                        if (r < 0) break
                        total += r
                    }
                } catch (_: Exception) {
                }
                out.append("3 sn icinde gelen video verisi: $total bayt\n")
            }

            Thread.sleep(300)
            out.append("\n--- sunucu gunlugu ---\n").append(log)
        } catch (e: Throwable) {
            out.append("ISTISNA: ${e}\n")
        } finally {
            try { sock?.close() } catch (_: Exception) {}
            try { proc?.destroy() } catch (_: Exception) {}
        }
        return out.toString()
    }
}
