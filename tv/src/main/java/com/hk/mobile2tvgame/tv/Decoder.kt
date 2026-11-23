package com.hk.mobile2tvgame.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import kotlin.concurrent.thread

/** H.264 donanim cozucu; ciktiyi hemen yuzeye basar (dusuk gecikme). */
class Decoder(width: Int, height: Int, surface: Surface) {

    private val codec: MediaCodec = MediaCodec.createDecoderByType("video/avc")

    @Volatile
    private var alive = true
    private val outThread: Thread

    // Bir kare cozucuye verilemezse referans zinciri bozulur; bozuk goruntu gostermek yerine
    // son saglam kare ekranda kalir ve bir sonraki keyframe beklenir.
    private var waitKey = false
    private var waitKeySince = 0L

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
            while (alive) {
                try {
                    var i = codec.dequeueOutputBuffer(info, 10_000)
                    while (i >= 0 && alive) {
                        codec.releaseOutputBuffer(i, info.size > 0)
                        i = codec.dequeueOutputBuffer(info, 0)
                    }
                } catch (_: Exception) {
                    if (!alive) break
                }
            }
        }
    }

    fun feed(data: ByteArray, size: Int, ptsUs: Long, config: Boolean) {
        if (!config) {
            if (waitKey) {
                // Keyframe gelene kadar P-kareleri at. Guvenlik agi: 1.5 sn icinde gelmezse yine de devam et.
                if (isKeyFrame(data, size) || SystemClock.elapsedRealtime() - waitKeySince > 1500) {
                    waitKey = false
                } else {
                    return
                }
            }
        }
        val i = codec.dequeueInputBuffer(50_000)
        if (i < 0) {
            if (!config) {
                waitKey = true
                waitKeySince = SystemClock.elapsedRealtime()
            }
            return
        }
        val buf = codec.getInputBuffer(i) ?: return
        buf.clear()
        buf.put(data, 0, size)
        codec.queueInputBuffer(i, 0, size, ptsUs, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
    }

    /** Annex-B akista IDR (NAL tipi 5) var mi? Baslik bayraklarindan bagimsiz, guvenilir tespit. */
    private fun isKeyFrame(buf: ByteArray, size: Int): Boolean {
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
                    if ((buf[nalIdx].toInt() and 0x1F) == 5) return true
                    i = nalIdx
                    continue
                }
            }
            i++
        }
        return false
    }

    fun release() {
        alive = false
        try { outThread.join(300) } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
