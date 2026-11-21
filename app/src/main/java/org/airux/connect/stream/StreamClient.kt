package org.airux.connect.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.airux.connect.net.Session
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The second screen / mirror: H.264 access units over a WebSocket into the hardware decoder, rendered
 *  straight to a Surface. Each frame carries the PC's time when it was ready; with the clock offset from
 *  pings, the app shows how long frames take from the PC to this screen. */
class StreamClient(
    private val surface: Surface,
    private val source: String,
    private val fps: Int,
    private val kbps: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onInfo(text: String)
        fun onVideoSize(width: Int, height: Int)
        fun onLatency(ms: Int, fps: Int)
        fun onEnded(why: String)
    }

    private var ws: WebSocket? = null
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var output: Thread? = null
    private var pinger: Thread? = null
    @Volatile private var offsetUs = 0L            // PC clock - phone clock
    @Volatile private var bestRtt = Long.MAX_VALUE
    private var gotKey = false
    private val lat = ArrayDeque<Long>()
    private var frames = 0
    private var fpsWindow = System.nanoTime()
    @Volatile private var shownFps = 0

    fun start() {
        val p = Session.paired.value ?: return listener.onEnded("not paired")
        running = true
        val c = MediaCodec.createDecoderByType("video/avc")
        val f = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        f.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        c.configure(f, surface, null, 0)
        c.start()
        codec = c
        output = Thread(::drain, "airox-decode-out").apply { start() }
        val url = "${Session.base()}/v1/screen/stream?source=$source&fps=$fps&kbps=$kbps"
        val req = Request.Builder().url(url).header("Authorization", p.auth).build()
        ws = Session.client().newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onInfo(if (source == "virtual") "Waiting for the PC (the first time, choose \"New Virtual Output\" there)…"
                                else "Waiting for the PC (the first time, choose the screen there)…")
                pinger = Thread(::pingLoop, "airox-stream-ping").apply { start() }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = frame(bytes.toByteArray())

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                when (m.optString("t")) {
                    "pong" -> {
                        val now = nowUs()
                        val rtt = now - m.optLong("ts")
                        if (rtt >= 0 && (bestRtt == Long.MAX_VALUE || rtt <= bestRtt + 2000)) {   // the fastest round trips
                            bestRtt = minOf(bestRtt, rtt)
                            offsetUs = m.optLong("server") - (m.optLong("ts") + now) / 2
                        }
                    }
                    "stream" -> when (m.optString("ev")) {
                        "stream" -> listener.onInfo("${m.optString("encoder")} · ${m.optInt("width")}x${m.optInt("height")}" +
                            if (m.optBoolean("virtual")) " · second screen" else "")
                        "error" -> listener.onEnded(m.optString("error"))
                        "ended" -> listener.onEnded("the PC stopped the stream${m.optString("error").let { if (it.isNotEmpty()) ": $it" else "" }}")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running) listener.onEnded(response?.let { "the PC refused (${it.code})" } ?: (t.message ?: "connection lost"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) listener.onEnded("closed")
            }
        })
    }

    private fun frame(data: ByteArray) {
        val c = codec ?: return
        if (data.size < 12) return
        val pcTime = ByteBuffer.wrap(data, 0, 8).order(ByteOrder.BIG_ENDIAN).long
        val au = data.copyOfRange(8, data.size)
        if (!gotKey) {
            if (!H264.hasKeyFrame(au)) return               // start at a key frame (SPS and PPS come with it)
            gotKey = true
        }
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return                               // the decoder is behind: drop (the PC skips to a key frame)
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(au)
            c.queueInputBuffer(idx, 0, au.size, pcTime, 0)
        } catch (e: IllegalStateException) { }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            when {
                idx >= 0 -> {
                    c.releaseOutputBuffer(idx, true)
                    if (bestRtt != Long.MAX_VALUE) {
                        lat.addLast(nowUs() + offsetUs - info.presentationTimeUs)
                        if (lat.size > 60) lat.removeFirst()
                    }
                    frames++
                    val t = System.nanoTime()
                    if (t - fpsWindow > 1_000_000_000) {
                        shownFps = frames
                        frames = 0
                        fpsWindow = t
                        if (lat.isNotEmpty()) listener.onLatency((lat.sorted()[lat.size / 2] / 1000).toInt(), shownFps)
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    listener.onVideoSize(w, h)
                }
            }
        }
    }

    private fun pingLoop() {
        var n = 0
        while (running) {
            ws?.send(JSONObject().put("t", "ping").put("ts", nowUs()).toString())
            Thread.sleep(if (n++ < 10) 200 else 2000)
        }
    }

    fun touch(phase: String, x: Float, y: Float, press: Boolean = true) {
        ws?.send(JSONObject().put("t", "touch").put("phase", phase).put("x", x.toDouble()).put("y", y.toDouble())
            .put("press", press).toString())
    }

    fun send(obj: JSONObject) { ws?.send(obj.toString()) }

    fun stop() {
        running = false
        ws?.close(1000, "bye")
        ws = null
        output?.join(500)
        codec?.run { try { stop() } catch (e: Exception) { }; release() }
        codec = null
    }

    private val wall0 = System.currentTimeMillis() * 1000
    private val nano0 = System.nanoTime()
    private fun nowUs() = wall0 + (System.nanoTime() - nano0) / 1000
}

object H264 {
    /** Does this Annex B access unit contain an IDR slice (NAL type 5)? */
    fun hasKeyFrame(au: ByteArray): Boolean {
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                if (au[i + 3].toInt() and 0x1F == 5) return true
                i += 3
            } else i++
        }
        return false
    }

    fun nalTypes(au: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                out.add(au[i + 3].toInt() and 0x1F)
                i += 3
            } else i++
        }
        return out
    }
}
