package org.airux.connect

import org.airux.connect.data.PairLink
import org.airux.connect.media.Recorder
import org.airux.connect.net.Lan
import org.airux.connect.net.Msg
import org.airux.connect.net.Pin
import org.airux.connect.net.Session
import org.airux.connect.stream.H264
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PairLinkTest {
    private val fp = "ab".repeat(32)
    private val code = "0f".repeat(16)

    @Test fun parsesTheQrLink() {
        val l = PairLink.parse("airox://pair?h=192.168.1.20%2C10.0.0.5&p=8766&fp=$fp&c=$code&n=airux-pc&id=3aa80cd0422098bb")!!
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), l.hosts)
        assertEquals(8766, l.port)
        assertEquals(fp, l.fingerprint)
        assertEquals("airux-pc", l.pcName)
        assertEquals("3aa80cd0422098bb", l.pcId)
    }

    @Test fun refusesAnythingElse() {
        assertNull(PairLink.parse("https://example.com/pair?h=1.2.3.4&p=1&fp=$fp&c=$code"))
        assertNull(PairLink.parse("airox://pair?h=1.2.3.4&p=8766&fp=short&c=$code"))
        assertNull(PairLink.parse("airox://pair?h=1.2.3.4&p=0&fp=$fp&c=$code"))
        assertNull(PairLink.parse("airox://pair?h=&p=8766&fp=$fp&c=$code"))
        assertNull(PairLink.parse("airox://pair?h=bad host!&p=8766&fp=$fp&c=$code"))
        assertNull(PairLink.parse("not a link"))
    }
}

@RunWith(AndroidJUnit4::class)
class PinTest {
    @Test fun matchesOnlyTheExactCertificate() {
        val der = "certificate bytes".toByteArray()
        val fp = Pin.sha256Hex(der)
        assertTrue(Pin.matches(der, fp))
        assertTrue(Pin.matches(der, fp.uppercase()))
        assertFalse(Pin.matches("other".toByteArray(), fp))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Pin.sha256Hex(ByteArray(0)))
    }
}

@RunWith(AndroidJUnit4::class)
class LanTest {
    @Test fun discoveryReplyMustNameThisPc() {
        assertEquals(8766, Lan.parseReply("AIROX!abc123 8766", "abc123"))
        assertNull(Lan.parseReply("AIROX!other 8766", "abc123"))
        assertNull(Lan.parseReply("garbage", "abc123"))
    }

    @Test fun magicPacket() {
        val p = Lan.magicPacket("00:11:22:AA:bb:cc")
        assertEquals(102, p.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, p.copyOfRange(0, 6))
        for (i in 0 until 16) assertArrayEquals(byteArrayOf(0, 0x11, 0x22, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
            p.copyOfRange(6 + i * 6, 12 + i * 6))
    }
}

@RunWith(AndroidJUnit4::class)
class WavTest {
    @Test fun headerIs16kMono16bit() {
        val pcm = ByteArray(3200) { it.toByte() }
        val w = Recorder.wav(pcm)
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        assertEquals(1, b.getShort(22).toInt())          // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())         // bits
        assertEquals(3200, b.getInt(40))
        assertEquals(44 + 3200, w.size)
    }
}

@RunWith(AndroidJUnit4::class)
class H264Test {
    private fun nal(t: Int) = byteArrayOf(0, 0, 0, 1, (0x60 or t).toByte(), 0x80.toByte(), 1, 2, 3)

    @Test fun findsKeyFrames() {
        assertTrue(H264.hasKeyFrame(nal(7) + nal(8) + nal(5)))
        assertFalse(H264.hasKeyFrame(nal(1) + nal(1)))
        assertEquals(listOf(7, 8, 5), H264.nalTypes(nal(7) + nal(8) + nal(5)))
    }
}

@RunWith(AndroidJUnit4::class)
class TranscriptTest {
    @Test fun replayedHistoryIsKeptOnce() {
        val a = Msg(1.0, "you", "hi")
        val b = Msg(2.0, "aira", "Hello, boss.")
        val merged = Session.merge(listOf(a), listOf(a, b, b))
        assertEquals(listOf(a, b), merged)
    }
}
