package org.airux.connect.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Push-to-talk recording as 16 kHz mono 16-bit WAV (what whisper.cpp on the PC wants). */
class Recorder {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var on = false
    @Volatile var level = 0f
        private set

    val recording get() = on

    @SuppressLint("MissingPermission")
    fun start() {
        if (on) return
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("the microphone is not available")
        }
        pcm.reset()
        rec = r
        on = true
        r.startRecording()
        thread = Thread {
            val buf = ByteArray(3200)
            while (on) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    pcm.write(buf, 0, n)
                    var peak = 0
                    for (i in 0 until n - 1 step 2) {
                        val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                        peak = maxOf(peak, kotlin.math.abs(s.toShort().toInt()))
                    }
                    level = peak / 32768f
                    if (pcm.size() > RATE * 2 * MAX_SECONDS) on = false
                }
            }
        }.apply { start() }
    }

    fun stop(): ByteArray {
        on = false
        thread?.join(1000)
        rec?.run { try { stop() } catch (e: Exception) { }; release() }
        rec = null
        return wav(pcm.toByteArray())
    }

    companion object {
        const val RATE = 16000
        const val MAX_SECONDS = 30

        fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
            val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
                .putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return b.array()
        }
    }
}

/** Plays AIRA's own voice (the WAV the PC made with Kokoro), or speaks with Android's voice if there is none. */
class Speaker(private val context: Context) {
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    fun playWav(bytes: ByteArray) {
        stop()
        val f = File(context.cacheDir, "aira-reply.wav").apply { writeBytes(bytes) }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
    }

    fun say(text: String) {
        stop()
        if (tts == null) {
            tts = TextToSpeech(context) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aira")
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aira")
        }
    }

    fun stop() {
        player?.run { try { stop() } catch (e: Exception) { }; release() }
        player = null
        tts?.stop()
    }
}
