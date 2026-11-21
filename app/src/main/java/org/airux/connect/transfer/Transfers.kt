package org.airux.connect.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.airux.connect.net.HttpError
import org.airux.connect.net.Session
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

data class Transfer(
    val id: String, val name: String, val size: Long, val up: Boolean,
    val done: Long = 0, val state: String = "waiting", val uri: Uri? = null, val error: String? = null,
)

/** Files both ways. Phone -> PC: resumable chunked upload checked by SHA-256. PC -> phone: ranged download
 *  (resumes after a drop), checked against the PC's SHA-256, saved in Downloads/AIROX Connect. */
object Transfers {
    val list = MutableStateFlow<List<Transfer>>(emptyList())
    private const val CHUNK = 4L * 1024 * 1024

    private fun update(id: String, f: (Transfer) -> Transfer) {
        list.value = list.value.map { if (it.id == id) f(it) else it }
    }

    private fun add(t: Transfer) { list.value = (list.value + t).takeLast(50) }

    fun clearFinished() { list.value = list.value.filter { it.state != "done" && it.state != "failed" } }

    // ---------------------------------------------------------------- phone -> PC
    fun upload(context: Context, uri: Uri) = Session.scope.launch {
        val (name, size) = describe(context, uri)
        val id = "u-" + UUID.randomUUID().toString().replace("-", "").take(20)
        add(Transfer(id, name, size, up = true, state = "checking"))
        try {
            val sha = context.contentResolver.openInputStream(uri)!!.use { sha256(it) }
            update(id) { it.copy(state = "sending") }
            var attempt = 0
            while (true) {
                val have = JSONObject(String(Session.call("GET", "/v1/files/upload/$id"))).optLong("received")
                if (have >= size && size > 0) break
                try {
                    val out = putFrom(context, uri, id, name, size, sha, have)
                    if (out.optBoolean("complete")) {
                        update(id) { it.copy(done = size, state = "done") }
                        return@launch
                    }
                } catch (e: IOException) {
                    if (e is HttpError && e.code != 409 || ++attempt > 5) throw e
                    kotlinx.coroutines.delay(1500L * attempt)       // resumes where the PC says
                }
            }
            update(id) { it.copy(done = size, state = "done") }
        } catch (e: Exception) {
            update(id) { it.copy(state = "failed", error = e.message) }
        }
    }

    private fun putFrom(context: Context, uri: Uri, id: String, name: String, size: Long, sha: String, start: Long): JSONObject {
        val q = "name=${enc(name)}&size=$size&sha256=$sha&start=$start"
        val len = size - start
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = len
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)!!.use { ins ->
                    ins.skipFully(start)
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (sent < len) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), len - sent).toInt())
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        update(id) { it.copy(done = start + sent) }
                    }
                }
            }
        }
        val p = Session.paired.value ?: throw IOException("not paired")
        val req = Request.Builder().url("${Session.base()}/v1/files/upload/$id?$q").header("Authorization", p.auth).put(body).build()
        Session.client().newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpError(r.code, try { JSONObject(text).optString("error") } catch (e: Exception) { "upload failed" })
            return JSONObject(text)
        }
    }

    // ---------------------------------------------------------------- PC -> phone
    fun download(context: Context, tid: String, name: String, size: Long, mime: String) = Session.scope.launch {
        if (list.value.any { it.id == tid && it.state != "failed" }) return@launch
        add(Transfer(tid, name, size, up = false, state = "receiving"))
        val part = File(context.cacheDir, "dl-$tid.part")
        try {
            var attempt = 0
            while (part.length() < size) {
                try {
                    fetch(tid, part, size)
                } catch (e: IOException) {
                    if (++attempt > 5) throw e
                    kotlinx.coroutines.delay(1500L * attempt)
                }
            }
            val want = JSONObject(String(Session.call("GET", "/v1/files/hash/$tid"))).getString("sha256")
            val got = part.inputStream().use { sha256(it) }
            if (got != want) { part.delete(); throw IOException("the file arrived damaged; try again") }
            val uri = withContext(Dispatchers.IO) { saveToDownloads(context, part, name, mime) }
            part.delete()
            Session.call("POST", "/v1/files/done/$tid")
            update(tid) { it.copy(done = size, state = "done", uri = uri) }
        } catch (e: Exception) {
            update(tid) { it.copy(state = "failed", error = e.message) }
        }
    }

    private fun fetch(tid: String, part: File, size: Long) {
        val have = part.length()
        val p = Session.paired.value ?: throw IOException("not paired")
        val req = Request.Builder().url("${Session.base()}/v1/files/download/$tid").header("Authorization", p.auth)
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        Session.client().newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "download failed (${r.code})")
            if (have > 0 && r.code != 206) part.delete()
            val ins = r.body!!.byteStream()
            val append = have > 0 && r.code == 206
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = if (append) have else 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    update(tid) { it.copy(done = done) }
                }
            }
        }
    }

    private fun saveToDownloads(context: Context, f: File, name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AIROX Connect")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("could not save to Downloads")
        cr.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }

    // ---------------------------------------------------------------- helpers
    fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) size = context.contentResolver.openInputStream(uri)?.use { s -> var n = 0L; val b = ByteArray(65536); while (true) { val r = s.read(b); if (r < 0) break; n += r }; n } ?: 0L
        return name.replace("/", "_") to size
    }

    fun sha256(ins: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) throw IOException("file shorter than expected"); left-- } else left -= s
        }
    }
}
