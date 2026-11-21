package org.airux.connect

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch
import org.airux.connect.media.Speaker
import org.airux.connect.net.Session
import org.airux.connect.service.ConnectService
import org.airux.connect.service.Notes
import org.airux.connect.service.handleBackgroundEvent

class App : Application() {
    @Volatile var foreground = false
        private set
    lateinit var speaker: Speaker
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        speaker = Speaker(this)
        Notes.channels(this)
        Session.init(this)
        Session.onEvent = { m ->
            handleBackgroundEvent(this, m, foreground)
            // AIRA's spoken reply to a question asked by voice on this phone: play her own voice here
            if (m.optString("t") == "aira" && m.optString("event") == "spoken" && Session.recentVoice() && Session.store.replyOnPhone) {
                val wav = m.optJSONObject("voice")?.optString("wav").orEmpty().substringAfterLast('/')
                Session.scope.launch {
                    try {
                        if (wav.isNotEmpty()) speaker.playWav(Session.bytes("/v1/aira/speech?name=$wav"))
                        else Session.transcript.value.lastOrNull { it.who == "aira" }?.let { speaker.say(it.text) }
                    } catch (e: Exception) {
                        Session.transcript.value.lastOrNull { it.who == "aira" }?.let { speaker.say(it.text) }
                    }
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                Session.start()
                // a foreground service may only be started while the app is on screen (Android 12+)
                if (Session.store.stayConnected) ConnectService.start(this@App)
            }
            override fun onStop(owner: LifecycleOwner) { foreground = false }
        })
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
