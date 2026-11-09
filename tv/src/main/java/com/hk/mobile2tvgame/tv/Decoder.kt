package com.hk.mobile2tvgame.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import kotlin.concurrent.thread

/** H.264 donanim cozucu; ciktiyi hemen yuzeye basar (dusuk gecikme). */
class Decoder(width: Int, height: Int, surface: Surface) {

    private val codec: MediaCodec = MediaCodec.createDecoderByType("video/avc")

    @Volatile
    private var alive = true
    private val outThread: Thread

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
        val i = codec.dequeueInputBuffer(100_000)
        if (i < 0) return
        val buf = codec.getInputBuffer(i) ?: return
        buf.clear()
        buf.put(data, 0, size)
        codec.queueInputBuffer(i, 0, size, ptsUs, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
    }

    fun release() {
        alive = false
        try { outThread.join(300) } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
