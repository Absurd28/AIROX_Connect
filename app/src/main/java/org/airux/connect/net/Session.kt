package org.airux.connect.net

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.airux.connect.data.PairLink
import org.airux.connect.data.Paired
import org.airux.connect.data.Store
import org.airux.connect.data.objects
import org.airux.connect.data.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class Conn {
    data object Unpaired : Conn()
    data object Connecting : Conn()
    data class Online(val host: String) : Conn()
    data class Offline(val why: String) : Conn()
}

data class Msg(val ts: Double, val who: String, val text: String, val tags: List<String> = emptyList(), val via: String = "")

data class Confirm(
    val id: String, val src: String, val tool: String, val summary: String, val risks: List<String>, val strong: Boolean,
    val expires: Long,
)

class HttpError(val code: Int, message: String) : IOException(message)

/** The connection to the paired PC: REST calls, the events WebSocket, AIRA's transcript and confirmations. */
object Session {
    lateinit var app: Context
        private set
    lateinit var store: Store
        private set

    val conn = MutableStateFlow<Conn>(Conn.Unpaired)
    val paired = MutableStateFlow<Paired?>(null)
    val transcript = MutableStateFlow<List<Msg>>(emptyList())
    val busy = MutableStateFlow(false)
    val airaUp = MutableStateFlow(false)
    val confirms = MutableStateFlow<List<Confirm>>(emptyList())
    val inputState = MutableStateFlow("off")
    val events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 128)

    /** Background work for the whole app. A failure there is logged, never a crash. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        android.util.Log.w("AIROXConnect", "background task failed", e)
    })
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val orphans = ConcurrentHashMap<String, JSONObject>()
    private var http: OkHttpClient? = null
    private var slow: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var loop: Job? = null
    @Volatile var host: String? = null
        private set
    @Volatile private var lastVoiceAt = 0L

    var onEvent: ((JSONObject) -> Unit)? = null      // set by the app (notifications, downloads, ring)

    fun init(context: Context) {
        app = context.applicationContext
        store = Store(app)
        store.load()?.let { use(it) }
    }

    private fun use(p: Paired) {
        paired.value = p
        http = Pin.client(p.fingerprint)
        slow = http!!.newBuilder().readTimeout(200, TimeUnit.SECONDS).build()
        start()
    }

    // ---------------------------------------------------------------- pairing
    suspend fun pair(link: PairLink, overrideHosts: List<String>? = null): Paired = withContext(Dispatchers.IO) {
        val client = Pin.client(link.fingerprint, readSeconds = 150)
        val body = JSONObject().put("code", link.code).put("name", deviceName()).put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("platform", "android")
        var last: Exception? = null
        for (h in overrideHosts ?: link.hosts) {
            try {
                val req = Request.Builder().url("https://${hostPart(h)}:${link.port}/v1/pair")
                    .post(body.toString().toRequestBody(JSON)).build()
                client.newCall(req).execute().use { r ->
                    val j = JSONObject(r.body?.string().orEmpty().ifEmpty { "{}" })
                    if (!r.isSuccessful) throw HttpError(r.code, j.optString("error", "pairing failed (${r.code})"))
                    val pc = j.getJSONObject("pc")
                    val hosts = listOf(h) + pc.optJSONArray("addresses")?.strings().orEmpty().filter { it != h }
                    val p = Paired(pc.getString("id"), pc.getString("name"), link.fingerprint, hosts, link.port,
                        j.getString("device_id"), j.getString("token"), pc.optJSONArray("macs")?.strings().orEmpty(),
                        pc.optString("user"))
                    store.save(p)
                    withContext(Dispatchers.Main) { use(p) }
                    return@withContext p
                }
            } catch (e: HttpError) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Could not reach ${link.pcName}: ${last?.message ?: "no address answered"}. Is the phone on the same network?")
    }

    fun unpair() {
        ws?.close(1000, "unpaired")
        loop?.cancel()
        store.clear()
        paired.value = null
        host = null
        conn.value = Conn.Unpaired
        transcript.value = emptyList()
        confirms.value = emptyList()
    }

    private fun deviceName(): String =
        android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: Build.MODEL

    private fun hostPart(h: String) = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h

    // ---------------------------------------------------------------- connection loop
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 1000L
            while (isActive) {
                val p = paired.value ?: break
                if (ws == null) {
                    conn.value = Conn.Connecting
                    try {
                        val h = findHost(p)
                        if (paired.value?.deviceId != p.deviceId) break      // unpaired meanwhile
                        if (h == null) {
                            conn.value = Conn.Offline("${p.pcName} is not reachable on this network")
                        } else {
                            host = h
                            openWs(p)
                        }
                    } catch (e: Exception) {
                        if (paired.value != null) conn.value = Conn.Offline(e.message ?: "connection failed")
                    }
                }
                delay(backoff)
                backoff = if (ws == null) (backoff * 2).coerceAtMost(15_000) else 1000L
            }
        }
    }

    fun reconnectNow() {
        ws?.cancel()
        ws = null
        loop?.cancel()
        start()
    }

    private fun reachable(p: Paired, h: String): Boolean = try {
        val c = http!!.newBuilder().readTimeout(3, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url("https://${hostPart(h)}:${p.port}/v1/hello").build()).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }

    private fun findHost(p: Paired): String? {
        host?.let { if (reachable(p, it)) return it }
        for (h in p.hosts) if (reachable(p, h)) return h
        val found = try { Lan.discover(p.pcId) } catch (e: Exception) { null } ?: return null
        if (reachable(p.copy(port = found.second), found.first)) {
            val hosts = listOf(found.first) + p.hosts.filter { it != found.first }
            store.updateHosts(hosts, found.second)
            paired.value = p.copy(hosts = hosts, port = found.second)
            return found.first
        }
        return null
    }

    fun base(): String {
        val p = paired.value ?: throw IOException("not paired")
        val h = host ?: throw IOException("${p.pcName} is not connected")
        return "https://${hostPart(h)}:${p.port}"
    }

    private fun openWs(p: Paired) {
        val req = Request.Builder().url("${base()}/v1/ws").header("Authorization", p.auth).build()
        ws = http!!.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(JSONObject(text)) } catch (e: Exception) { }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "closed ($code)", code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(webSocket, t.message ?: "connection lost", response?.code ?: 0)
        })
    }

    private fun lost(w: WebSocket, why: String, code: Int) {
        if (ws !== w) return
        ws = null
        airaUp.value = false
        pending.values.forEach { it.completeExceptionally(IOException("connection lost")) }
        pending.clear()
        if (code == 401 || code == 4001) {
            conn.value = Conn.Offline("The PC removed this phone. Pair again.")
            loop?.cancel()
        } else if (paired.value != null) {
            conn.value = Conn.Offline(why)
        }
    }

    // ---------------------------------------------------------------- events from the PC
    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "hello" -> {
                conn.value = Conn.Online(host ?: "")
                airaUp.value = m.optBoolean("aira")
                busy.value = m.optBoolean("busy")
                inputState.value = m.optString("input", "off")
                val list = m.optJSONArray("transcript")?.objects().orEmpty().mapNotNull { toMsg(it) }
                transcript.value = merge(transcript.value, list)
                sendStatus()
            }
            "aira" -> aira(m)
            "confirm" -> addConfirm(Confirm(m.getString("id"), "connect", m.optString("tool"), m.optString("summary"),
                m.optJSONArray("risks")?.strings().orEmpty(), m.optBoolean("strong"),
                System.currentTimeMillis() + m.optLong("timeout", 120) * 1000))
            "confirm_done" -> confirms.value = confirms.value.filter { it.id != m.optString("id") }
            "result" -> {
                val rid = m.optString("rid")
                if (pending.remove(rid)?.complete(m) == null) {
                    orphans[rid] = m                 // arrived before run() started waiting
                    if (orphans.size > 50) orphans.keys.firstOrNull()?.let { orphans.remove(it) }
                }
            }
            "input" -> inputState.value = m.optString("state", "off")
            "pong" -> {}
        }
        events.tryEmit(m)
        onEvent?.invoke(m)
    }

    private fun aira(m: JSONObject) {
        when (m.optString("event")) {
            "you", "aira" -> toMsg(m)?.let { transcript.value = merge(transcript.value, listOf(it)) }
            "thinking", "listening" -> busy.value = true
            "idle" -> busy.value = false
            "link" -> airaUp.value = m.optBoolean("up")
            "error" -> transcript.value = transcript.value + Msg(System.currentTimeMillis() / 1000.0, "error", m.optString("text"))
            "confirm" -> addConfirm(Confirm(m.getString("id"), "aira", m.optString("tool"), airaSummary(m),
                m.optJSONArray("risks")?.strings().orEmpty(),
                m.optJSONArray("risks")?.strings().orEmpty().any { it in STRONG },
                System.currentTimeMillis() + m.optLong("timeout", 120) * 1000))
            "confirm_done" -> confirms.value = confirms.value.filter { it.id != m.optString("id") }
        }
    }

    private fun airaSummary(m: JSONObject): String {
        val args = m.optJSONObject("args")
        val what = args?.keys()?.asSequence()?.joinToString(", ") { "$it: ${args.opt(it)}" }.orEmpty()
        return "AIRA wants to use ${m.optString("tool")}${if (what.isNotEmpty()) " ($what)" else ""}. Allow it?"
    }

    private fun addConfirm(c: Confirm) {
        if (confirms.value.none { it.id == c.id }) confirms.value = confirms.value + c
    }

    private fun toMsg(m: JSONObject): Msg? {
        val e = m.optString("event")
        if (e != "you" && e != "aira") return null
        return Msg(m.optDouble("ts", 0.0), e, m.optString("text"), m.optJSONArray("tags")?.strings().orEmpty(), m.optString("via"))
    }

    /** The shell replays its history after a reconnect: keep each turn once. */
    fun merge(old: List<Msg>, new: List<Msg>): List<Msg> {
        val seen = old.map { it.ts to it.text }.toMutableSet()
        val out = old.toMutableList()
        for (n in new) if (seen.add(n.ts to n.text)) out.add(n)
        return out.sortedBy { it.ts }.takeLast(200)
    }

    // ---------------------------------------------------------------- talking to the PC
    fun send(obj: JSONObject): Boolean = ws?.send(obj.toString()) ?: false

    fun say(text: String) = send(JSONObject().put("t", "say").put("text", text))

    fun answer(c: Confirm, yes: Boolean) {
        send(JSONObject().put("t", "answer").put("id", c.id).put("yes", yes).put("src", c.src))
        confirms.value = confirms.value.filter { it.id != c.id }
    }

    fun input(obj: JSONObject) = send(obj.put("t", "input"))

    fun sendStatus() {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        send(JSONObject().put("t", "status").put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging))
    }

    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(String(call("GET", path))) }

    suspend fun bytes(path: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) { call("GET", path, headers = headers, long = path.startsWith("/v1/screen")) }

    suspend fun post(path: String, body: ByteArray, type: String = "application/json"): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(String(call("POST", path, body, type)).ifEmpty { "{}" })
    }

    fun call(method: String, path: String, body: ByteArray? = null, type: String = "application/json",
             headers: Map<String, String> = emptyMap(), long: Boolean = false): ByteArray {
        val p = paired.value ?: throw IOException("not paired")
        val b = Request.Builder().url(base() + path).header("Authorization", p.auth)
        headers.forEach { (k, v) -> b.header(k, v) }
        when (method) {
            "GET" -> b.get()
            else -> b.method(method, (body ?: ByteArray(0)).toRequestBody(type.toMediaType()))
        }
        (if (long) slow!! else http!!).newCall(b.build()).execute().use { r ->
            val data = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) {
                val msg = try { JSONObject(String(data)).optString("error") } catch (e: Exception) { "" }
                throw HttpError(r.code, msg.ifEmpty { "the PC answered ${r.code}" })
            }
            return data
        }
    }

    fun client(): OkHttpClient = http ?: throw IOException("not paired")

    /** An action through the PC's permission broker. The result comes back on the WebSocket; if it needs
     *  confirmation, a confirm message arrives first and the user answers it in the app. */
    suspend fun run(scope: String, tool: String, args: JSONObject = JSONObject(), timeoutMs: Long = 200_000): JSONObject {
        if (ws == null) throw IOException("not connected to the PC")
        val r = post("/v1/run", JSONObject().put("scope", scope).put("tool", tool).put("args", args).toString().toByteArray())
        val rid = r.getString("rid")
        val d = CompletableDeferred<JSONObject>()
        pending[rid] = d
        orphans.remove(rid)?.let { pending.remove(rid); return it }
        return try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(rid) }
    }

    fun markVoice() { lastVoiceAt = System.currentTimeMillis() }
    fun recentVoice(): Boolean = System.currentTimeMillis() - lastVoiceAt < 180_000

    private val JSON = "application/json".toMediaType()
    val STRONG = setOf("delete", "root", "install", "exec")
}

/** One-line explanation of a broker result, for a toast or snackbar. */
fun JSONObject.outcome(): String = when {
    !optBoolean("allowed", true) -> if (optString("decision").startsWith("denied")) "Cancelled." else optString("decision")
    !optBoolean("ok") -> "That did not work: ${optString("error")}"
    else -> optJSONObject("data")?.optString("detail")?.ifEmpty { null } ?: "Done."
}

fun JSONArray?.orEmptyList(): List<JSONObject> = this?.objects().orEmpty()
