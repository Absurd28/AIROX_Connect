package org.airux.connect.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The paired PC. The device token is stored encrypted with an AES-GCM key kept in the Android Keystore. */
data class Paired(
    val pcId: String,
    val pcName: String,
    val fingerprint: String,
    val hosts: List<String>,
    val port: Int,
    val deviceId: String,
    val token: String,
    val macs: List<String>,
    val user: String,
) {
    val auth get() = "Bearer $deviceId.$token"
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("airox-connect", Context.MODE_PRIVATE)

    fun load(): Paired? {
        val raw = prefs.getString("pc", null) ?: return null
        val enc = prefs.getString("token", null) ?: return null
        val token = try { decrypt(enc) } catch (e: Exception) { return null }
        val j = JSONObject(raw)
        return Paired(
            j.getString("id"), j.getString("name"), j.getString("fp"), j.getJSONArray("hosts").strings(),
            j.getInt("port"), j.getString("device"), token, j.optJSONArray("macs")?.strings() ?: emptyList(),
            j.optString("user"),
        )
    }

    fun save(p: Paired) {
        val j = JSONObject().put("id", p.pcId).put("name", p.pcName).put("fp", p.fingerprint)
            .put("hosts", JSONArray(p.hosts)).put("port", p.port).put("device", p.deviceId)
            .put("macs", JSONArray(p.macs)).put("user", p.user)
        prefs.edit().putString("pc", j.toString()).putString("token", encrypt(p.token)).apply()
    }

    fun updateHosts(hosts: List<String>, port: Int) {
        val raw = prefs.getString("pc", null) ?: return
        val j = JSONObject(raw).put("hosts", JSONArray(hosts)).put("port", port)
        prefs.edit().putString("pc", j.toString()).apply()
    }

    fun clear() = prefs.edit().remove("pc").remove("token").apply()

    var stayConnected: Boolean
        get() = prefs.getBoolean("stay", true)
        set(v) = prefs.edit().putBoolean("stay", v).apply()

    var replyOnPhone: Boolean
        get() = prefs.getBoolean("reply_phone", true)
        set(v) = prefs.edit().putBoolean("reply_phone", v).apply()

    var pcAlerts: Boolean
        get() = prefs.getBoolean("alerts", true)
        set(v) = prefs.edit().putBoolean("alerts", v).apply()

    var screenScale: Int          // second screen quality: 0 balanced, 1 sharp, 2 fast
        get() = prefs.getInt("screen_q", 0)
        set(v) = prefs.edit().putInt("screen_q", v).apply()

    // ---------------------------------------------------------------- Keystore
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(text.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return String(c.doFinal(all.copyOfRange(12, all.size)))
    }

    companion object {
        private const val ALIAS = "airox-connect-token"
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
