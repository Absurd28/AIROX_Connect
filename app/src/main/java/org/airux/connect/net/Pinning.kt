package org.airux.connect.net

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Trust exactly one certificate: the PC's, by the SHA-256 of its DER bytes from the pairing QR code.
 *  No certificate authority and no trust on first use; host names do not matter (the pin is the identity). */
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (!Pin.matches(leaf.encoded, fingerprint)) throw CertificateException("this is not your PC (certificate pin mismatch)")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

object Pin {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun matches(der: ByteArray, fingerprint: String): Boolean {
        val got = sha256Hex(der).toByteArray()
        return MessageDigest.isEqual(got, fingerprint.lowercase().toByteArray())
    }

    fun client(fingerprint: String, readSeconds: Long = 30): OkHttpClient {
        val tm = PinnedTrustManager(fingerprint)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val spec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(spec))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
