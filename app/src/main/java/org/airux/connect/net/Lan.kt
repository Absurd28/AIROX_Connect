package org.airux.connect.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Find the PC again when its address changed (UDP broadcast, only the PC with this id answers),
 *  and wake it with a Wake-on-LAN magic packet. */
object Lan {
    const val DISCOVERY_PORT = 8767

    fun discover(pcId: String, timeoutMs: Int = 1500): Pair<String, Int>? {
        if (pcId.isEmpty()) return null
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = timeoutMs
            val msg = "AIROX?$pcId".toByteArray()
            for (dest in listOf("255.255.255.255")) {
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(dest), DISCOVERY_PORT))
            }
            val buf = ByteArray(256)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { return null }
                val text = String(p.data, 0, p.length)
                parseReply(text, pcId)?.let { port -> return p.address.hostAddress!! to port }
            }
        }
        return null
    }

    fun parseReply(text: String, pcId: String): Int? {
        val prefix = "AIROX!$pcId "
        return if (text.startsWith(prefix)) text.removePrefix(prefix).trim().toIntOrNull() else null
    }

    fun magicPacket(mac: String): ByteArray {
        val m = mac.split(':', '-').map { it.toInt(16).toByte() }
        require(m.size == 6) { "not a MAC address: $mac" }
        val out = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) m.toByteArray().copyInto(out, 6 + i * 6)
        return out
    }

    fun wake(macs: List<String>): Int {
        var sent = 0
        DatagramSocket().use { s ->
            s.broadcast = true
            for (mac in macs) {
                val pkt = try { magicPacket(mac) } catch (e: Exception) { continue }
                for (port in listOf(9, 7)) {
                    s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), port))
                }
                sent++
            }
        }
        return sent
    }
}
