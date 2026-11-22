package org.airux.connect.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.airux.connect.stream.StreamClient
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** Full-screen second screen (or mirror) of the PC, with touch: tap = click, drag = drag, long press =
 *  right click, two fingers = scroll. */
class StreamActivity : Activity(), StreamClient.Listener {
    private lateinit var surface: SurfaceView
    private lateinit var info: TextView
    private lateinit var root: FrameLayout
    private var client: StreamClient? = null
    private val ui = Handler(Looper.getMainLooper())
    private var videoW = 1920
    private var videoH = 1080
    @Volatile var lastLatency = -1
        private set
    @Volatile var framesShown = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; setPadding(16, 10, 16, 10); setBackgroundColor(0x99000000.toInt())
            text = "Connecting…"; contentDescription = "streamInfo"
        }
        root.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val close = TextView(this).apply {
            text = "  ✕  "; setTextColor(Color.WHITE); textSize = 20f; setBackgroundColor(0x99000000.toInt())
            setOnClickListener { finish() }; contentDescription = "closeStream"
        }
        root.addView(close, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(root)
        immersive()
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startStream(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { client?.stop(); client = null }
        })
        setupTouch()
        current = this
    }

    private fun immersive() {
        // The compat controller also works on Android 10 (API 29), where Window.insetsController doesn't exist.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startStream(holder: SurfaceHolder) {
        if (client != null) return
        val q = intent.getIntExtra("quality", 0)
        val (fps, kbps) = when (q) { 1 -> 60 to 20000; 2 -> 60 to 6000; else -> 60 to 12000 }
        client = StreamClient(holder.surface, intent.getStringExtra("source") ?: "virtual", fps, kbps, this).also {
            try { it.start() } catch (e: Exception) { onEnded(e.message ?: "could not start") }
        }
    }

    override fun onInfo(text: String) { Log.i(TAG, "info: $text"); ui.post { info.text = text } }

    override fun onVideoSize(width: Int, height: Int) { Log.i(TAG, "video ${width}x$height"); ui.post {
        videoW = width; videoH = height
        val vw = root.width.toFloat(); val vh = root.height.toFloat()
        if (vw <= 0 || vh <= 0) return@post
        val scale = minOf(vw / width, vh / height)
        surface.layoutParams = FrameLayout.LayoutParams((width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER)
    } }

    override fun onLatency(ms: Int, fps: Int) {
        lastLatency = ms
        framesShown += fps
        ui.post { info.text = "≈ $ms ms PC → screen · $fps fps" }
    }

    override fun onEnded(why: String) { Log.i(TAG, "ended: $why"); ui.post { info.text = "Stopped: $why" } }

    // ---------------------------------------------------------------- touch
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        var dragging = false
        var longPressed = false
        var twoFinger = false
        var lastMidY = 0f
        val longPress = Runnable {
            if (!dragging && !twoFinger) {
                longPressed = true
                client?.send(JSONObject().put("t", "button").put("button", "right").put("down", true))
                client?.send(JSONObject().put("t", "button").put("button", "right").put("down", false))
            }
        }
        surface.setOnTouchListener { v: View, e: MotionEvent ->
            val x = (e.getX(0) / v.width).coerceIn(0f, 1f)
            val y = (e.getY(0) / v.height).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = System.currentTimeMillis(); downX = e.x; downY = e.y
                    dragging = false; longPressed = false; twoFinger = false
                    client?.touch("down", x, y, press = false)          // move there first; press when it is a tap or drag
                    ui.postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    twoFinger = true; ui.removeCallbacks(longPress)
                    lastMidY = (e.getY(0) + e.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> {
                    if (twoFinger && e.pointerCount >= 2) {
                        val mid = (e.getY(0) + e.getY(1)) / 2
                        val dy = mid - lastMidY
                        if (abs(dy) > 4) { client?.send(JSONObject().put("t", "scroll").put("dx", 0).put("dy", (-dy / 6).toDouble())); lastMidY = mid }
                    } else if (!twoFinger && !longPressed) {
                        if (!dragging && hypot(e.x - downX, e.y - downY) > 18) {
                            dragging = true; ui.removeCallbacks(longPress)
                            client?.send(JSONObject().put("t", "button").put("button", "left").put("down", true))
                        }
                        if (dragging) client?.touch("move", x, y, press = false)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (dragging) {
                        client?.touch("move", x, y, press = false)
                        client?.send(JSONObject().put("t", "button").put("button", "left").put("down", false))
                    } else if (!twoFinger && !longPressed && System.currentTimeMillis() - downAt < 400) {
                        client?.send(JSONObject().put("t", "button").put("button", "left").put("down", true))
                        client?.send(JSONObject().put("t", "button").put("button", "left").put("down", false))
                    }
                }
                MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
            }
            true
        }
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AIROXStream"
        @Volatile var current: StreamActivity? = null
            private set

        fun intent(ctx: Context, source: String, quality: Int): Intent =
            Intent(ctx, StreamActivity::class.java).putExtra("source", source).putExtra("quality", quality)
    }
}
