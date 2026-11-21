package org.airux.connect.data

import java.net.URI
import java.net.URLDecoder

/** The pairing link the PC shows as a QR code:
 *  airox://pair?h=<hosts>&p=<port>&fp=<sha-256 of the certificate>&c=<one-time code>&n=<PC name>&id=<PC id> */
data class PairLink(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val code: String,
    val pcName: String,
    val pcId: String,
) {
    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val HEX32 = Regex("^[0-9a-f]{32}$")
        private val HOST = Regex("^[0-9A-Za-z.:%-]{1,64}$")

        fun parse(text: String): PairLink? {
            val uri = try { URI(text.trim()) } catch (e: Exception) { return null }
            if (uri.scheme != "airox" || uri.host != "pair") return null
            val q = (uri.rawQuery ?: return null).split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val hosts = (q["h"] ?: return null).split(",").map { it.trim() }.filter { it.isNotEmpty() && HOST.matches(it) }
            val port = q["p"]?.toIntOrNull() ?: return null
            val fp = q["fp"]?.lowercase() ?: return null
            val code = q["c"]?.lowercase() ?: return null
            if (hosts.isEmpty() || port !in 1..65535 || !HEX64.matches(fp) || !HEX32.matches(code)) return null
            return PairLink(hosts, port, fp, code, (q["n"] ?: "PC").take(60), (q["id"] ?: "").take(32))
        }
    }
}
