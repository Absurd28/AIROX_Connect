package org.airux.connect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.airux.connect.R
import org.airux.connect.net.Conn
import org.airux.connect.net.Session
import org.airux.connect.ui.MainActivity
import org.json.JSONObject

object Notes {
    const val STATUS = "status"
    const val ALERTS = "alerts"
    const val CONFIRM = "confirm"
    const val FILES = "files"
    const val RING = "ring"
    const val STATUS_ID = 1
    const val RING_ID = 2

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STATUS, "Connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(ALERTS, "PC alerts (AIRA, Phagos, ...)", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CONFIRM, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH))
    }

    fun open(ctx: Context, tab: String = ""): PendingIntent = PendingIntent.getActivity(
        ctx, tab.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun show(ctx: Context, channel: String, id: Int, title: String, text: String, tab: String = "",
             actions: List<Pair<String, PendingIntent>> = emptyList()) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val b = NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat_airox).setContentTitle(title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(open(ctx, tab))
        actions.forEach { (label, pi) -> b.addAction(0, label, pi) }
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (e: SecurityException) { }
    }

    fun action(ctx: Context, what: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, (what + id).hashCode(), Intent(ctx, ActionReceiver::class.java).setAction(what).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Keeps the connection while the app is in the background ("stay connected"), so PC alerts, approvals,
 *  incoming files and "ring my phone" arrive. Nothing leaves the local network. */
class ConnectService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = status("Connecting…")
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notes.STATUS_ID, n, type)
        Session.start()
        job?.cancel()
        job = Session.scope.launch {
            Session.conn.collectLatest { c ->
                val text = when (c) {
                    is Conn.Online -> "Connected to ${Session.paired.value?.pcName}"
                    is Conn.Connecting -> "Connecting…"
                    is Conn.Offline -> c.why
                    Conn.Unpaired -> { stopSelf(); "Not paired" }
                }
                try { NotificationManagerCompat.from(this@ConnectService).notify(Notes.STATUS_ID, status(text)) } catch (e: SecurityException) { }
            }
        }
        return START_STICKY
    }

    private fun status(text: String): Notification = NotificationCompat.Builder(this, Notes.STATUS)
        .setSmallIcon(R.drawable.ic_stat_airox).setContentTitle("AIROX Connect").setContentText(text)
        .setOngoing(true).setContentIntent(Notes.open(this)).build()

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            if (Session.paired.value == null) return
            try { ctx.startForegroundService(Intent(ctx, ConnectService::class.java)) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ConnectService::class.java))
    }
}

/** "Ring my phone": loud (alarm volume) for up to 30 seconds, until stopped. */
object Ringer {
    private var tone: Ringtone? = null
    private val h = Handler(Looper.getMainLooper())

    fun ring(ctx: Context) {
        h.post {
            stop(ctx)
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            tone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            Notes.show(ctx, Notes.RING, Notes.RING_ID, "Ringing", "AIRA was asked to find this phone.",
                actions = listOf("Stop" to Notes.action(ctx, ActionReceiver.STOP_RING, "")))
            h.postDelayed({ stop(ctx) }, 30_000)
        }
    }

    fun stop(ctx: Context) {
        tone?.stop()
        tone = null
        NotificationManagerCompat.from(ctx).cancel(Notes.RING_ID)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            STOP_RING -> Ringer.stop(context)
            DENY -> {
                val id = intent.getStringExtra("id") ?: return
                Session.confirms.value.firstOrNull { it.id == id }?.let { Session.answer(it, false) }
                NotificationManagerCompat.from(context).cancel(id.hashCode())
            }
        }
    }

    companion object {
        const val STOP_RING = "org.airux.connect.STOP_RING"
        const val DENY = "org.airux.connect.DENY"
    }
}

/** What the app does with PC events when it is not on screen. */
fun handleBackgroundEvent(ctx: Context, m: JSONObject, foreground: Boolean) {
    when (m.optString("t")) {
        "notification" -> if (Session.store.pcAlerts) Notes.show(ctx, Notes.ALERTS, m.toString().hashCode(),
            m.optString("title").ifEmpty { m.optString("app") }, m.optString("body"), "home")
        "confirm" -> if (!foreground) {
            val id = m.optString("id")
            Notes.show(ctx, Notes.CONFIRM, id.hashCode(), "Approve on your phone", m.optString("summary"), "confirm",
                listOf("Deny" to Notes.action(ctx, ActionReceiver.DENY, id)))
        }
        "aira" -> if (m.optString("event") == "confirm" && !foreground) {
            val id = m.optString("id")
            Notes.show(ctx, Notes.CONFIRM, id.hashCode(), "AIRA asks", "AIRA wants to use ${m.optString("tool")}. Open to answer.",
                "aira", listOf("Deny" to Notes.action(ctx, ActionReceiver.DENY, id)))
        }
        "ring" -> Ringer.ring(ctx)
        "incoming" -> {
            val files = m.optJSONArray("files") ?: return
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                org.airux.connect.transfer.Transfers.download(ctx, f.getString("tid"), f.getString("name"), f.getLong("size"),
                    f.optString("mime"))
            }
            Notes.show(ctx, Notes.FILES, "incoming".hashCode(), "Files from your PC",
                "${files.length()} file(s) arriving in Downloads/AIROX Connect", "files")
        }
    }
}
