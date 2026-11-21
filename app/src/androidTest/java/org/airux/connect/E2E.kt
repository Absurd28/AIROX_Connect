package org.airux.connect

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.airux.connect.data.PairLink
import org.airux.connect.media.Recorder
import org.airux.connect.net.Conn
import org.airux.connect.net.Pin
import org.airux.connect.net.Session
import org.airux.connect.net.outcome
import org.airux.connect.transfer.Transfers
import org.airux.connect.ui.MainActivity
import org.airux.connect.ui.StreamActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.security.MessageDigest
import javax.net.ssl.SSLException

/** End to end against AIRUX in QEMU (tests/connect_check.py runs it and plays the person at the PC).
 *  Arguments: pairUri (with the host the emulator reaches), voiceWav (a spoken command on the device). */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2E {
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun step(s: String) = Log.i(TAG, "STEP $s")
    private fun result(k: String, v: Any?) = Log.i(TAG, "RESULT $k=$v")

    private fun until(seconds: Int, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            if (try { cond() } catch (e: Exception) { false }) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Tap like a person (UiAutomator), not through Compose's test rule: the rule lays out on the test thread. */
    private fun tap(tag: String, seconds: Long = 30) {
        val o = device.wait(Until.findObject(By.res(tag)), seconds * 1000) ?: throw AssertionError("no '$tag' on screen")
        o.click()
    }

    private fun approveInUi(expectText: String? = null, yes: Boolean = true) {
        val t = device.wait(Until.findObject(By.res("confirmText")), 60_000) ?: throw AssertionError("no confirmation shown")
        if (expectText != null) assertTrue("confirmation says '${t.text}'", t.text.contains(expectText))
        tap(if (yes) "confirmYes" else "confirmNo")
    }

    private fun main(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    @get:Rule val name = TestName()

    @Before fun connected() {
        // after t01 (which pairs afresh) every test expects the paired PC; wait until the app is talking to it
        if (!name.methodName.startsWith("t01") && Session.paired.value != null) {
            Session.start()
            until(90, "the connection to the PC") { Session.conn.value is Conn.Online }
        }
    }

    @Test fun t01_pair() {
        Session.unpair()
        val uri = args.getString("pairUri") ?: error("pairUri argument missing")
        assertNotNull(PairLink.parse(uri))
        step("pair_waiting")
        ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(ctx, MainActivity::class.java))
        assertNotNull("the main screen", device.wait(Until.findObject(By.res("pcName")), 150_000))
        until(30, "connection") { Session.conn.value is Conn.Online }
        result("paired", Session.paired.value?.pcName)
        step("paired")
    }

    @Test fun t02_refusals() = runBlocking<Unit> {
        val p = Session.paired.value!!
        val base = Session.base()
        val noAuth = Session.client().newCall(Request.Builder().url("$base/v1/info").build()).execute().use { it.code }
        val badAuth = Session.client().newCall(Request.Builder().url("$base/v1/info")
            .header("Authorization", "Bearer ${p.deviceId}.wrong").build()).execute().use { it.code }
        val wrongPin = try {
            Pin.client("00".repeat(32)).newCall(Request.Builder().url("$base/v1/hello").build()).execute().use { "answered ${it.code}" }
        } catch (e: SSLException) { "refused" } catch (e: Exception) { "refused (${e.javaClass.simpleName})" }
        result("no_token", noAuth); result("wrong_token", badAuth); result("wrong_pin", wrongPin)
        assertEquals(401, noAuth); assertEquals(401, badAuth); assertTrue(wrongPin.startsWith("refused"))
    }

    @Test fun t03_aira_text() {
        main()
        tap("tab_aira")
        val before = Session.transcript.value.count { it.who == "aira" }
        val box = device.wait(Until.findObject(By.res("sayText")), 30_000) ?: throw AssertionError("no message box")
        box.click()
        val q = "How much free disk space do I have in my home folder?"
        box.text = q
        if (device.wait(Until.findObject(By.res("send")), 5_000) == null) {
            // setText does not always reach Compose's text state: type it, as a keyboard would
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input text " + q.replace(" ", "%s").replace("?", "")).close()
        }
        tap("send")
        until(300, "AIRA's reply") { Session.transcript.value.count { it.who == "aira" } > before }
        val r = Session.transcript.value.last { it.who == "aira" }
        result("aira_text", r.text.replace("\n", " ").take(160))
        until(60, "AIRA idle") { !Session.busy.value }
    }

    @Test fun t04_aira_voice() = runBlocking<Unit> {
        val path = args.getString("voiceWav") ?: error("voiceWav argument missing")
        // the file was pushed by adb (owned by the shell): read it through the shell
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        val wav = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        assertTrue("the voice recording is readable (${wav.size} bytes)", wav.size > 1000)
        val before = Session.transcript.value.size
        Session.markVoice()
        Session.post("/v1/aira/voice", wav, "audio/wav")
        until(300, "voice turn") { Session.transcript.value.drop(before).any { it.who == "you" && it.via.contains("phone") } &&
            Session.transcript.value.drop(before).any { it.who == "aira" } }
        val you = Session.transcript.value.drop(before).first { it.who == "you" }
        val reply = Session.transcript.value.drop(before).first { it.who == "aira" }
        result("voice_heard", you.text); result("voice_reply", reply.text.replace("\n", " ").take(160))
        until(60, "AIRA idle") { !Session.busy.value }
    }

    @Test fun t05_tasks() {
        main()
        fun find(tag: String): JSONObject? = runBlocking {
            Session.get("/v1/tasks/processes").getJSONArray("groups").let { gs ->
                (0 until gs.length()).flatMap { i -> gs.getJSONObject(i).getJSONArray("procs").let { ps -> (0 until ps.length()).map { ps.getJSONObject(it) } } }
            }.firstOrNull { it.optString("cmdline").contains(tag) }
        }
        val deny = find("sleep 4242") ?: error("test process 'sleep 4242' is not listed")
        val end = find("sleep 4243") ?: error("test process 'sleep 4243' is not listed")
        result("listed", "sleep 4242 pid ${deny.optInt("pid")}, sleep 4243 pid ${end.optInt("pid")}")
        for ((p, yes) in listOf(deny to false, end to true)) {
            val r = Session.scope.async {
                Session.run("taskmgr", "end_task", JSONObject().put("pid", p.optInt("pid")).put("start", p.optLong("start")))
            }
            approveInUi("End sleep", yes)
            val out = runBlocking { r.await() }
            result(if (yes) "end_yes" else "end_no", out.toString().take(200))
            if (yes) assertTrue(out.optBoolean("ok") && out.getJSONObject("data").optBoolean("verified"))
            else assertTrue(!out.optBoolean("allowed"))
        }
        step("tasks_done")
        // the performance view answers too
        val perf = runBlocking { Session.get("/v1/tasks/performance") }
        assertTrue(perf.getJSONObject("cpu").getJSONArray("history").length() > 0)
    }

    @Test fun t06_files() {
        main()
        // phone -> PC
        val f = File(ctx.cacheDir, "phone-photo.jpg")
        val data = ByteArray(3_000_000).also { java.util.Random(7).nextBytes(it) }
        f.writeBytes(data)
        result("upload_sha256", sha(data))
        Transfers.upload(ctx, Uri.fromFile(f))
        until(120, "upload") { Transfers.list.value.any { it.name == "phone-photo.jpg" && it.state == "done" } }
        step("uploaded")
        // PC -> phone (confirmed on the phone)
        val r = Session.scope.async {
            Session.run("connect", "send_files", JSONObject().put("paths", JSONArray(listOf("~/Pictures/e2e.jpg"))))
        }
        approveInUi("e2e.jpg", true)
        val out = runBlocking { r.await() }
        val o = out.getJSONObject("data").getJSONArray("offers").getJSONObject(0)
        Transfers.download(ctx, o.getString("tid"), o.getString("name"), o.getLong("size"), o.optString("mime"))
        until(120, "download") { Transfers.list.value.any { it.id == o.getString("tid") && it.state == "done" } }
        val t = Transfers.list.value.first { it.id == o.getString("tid") }
        val got = ctx.contentResolver.openInputStream(t.uri!!)!!.use { it.readBytes() }
        result("download_sha256", sha(got))
        step("downloaded")
    }

    @Test fun t07_clipboard_link() = runBlocking<Unit> {
        val pc = Session.get("/v1/clipboard").getJSONObject("data").getString("text")
        result("pc_clipboard", pc)
        val set = Session.run("connect", "clipboard_set", JSONObject().put("text", "from the phone 1234"))
        result("clipboard_set", set.optBoolean("ok") && set.getJSONObject("data").optBoolean("verified"))
        val link = Session.scope.async { Session.run("connect", "open_link", JSONObject().put("url", "https://example.com/?airox=e2e")) }
        withTimeoutOrNull(60_000) { Session.confirms.first { l -> l.any { it.tool == "open_link" } } }
            ?.first { it.tool == "open_link" }?.let { Session.answer(it, true) }
        result("open_link", link.await().outcome())
        step("link_opened")
    }

    @Test fun t08_touchpad_media() = runBlocking<Unit> {
        step("input_start")
        Session.input(JSONObject().put("op", "start"))
        until(120, "remote input") { Session.inputState.value == "ready" }
        step("input_ready")
        repeat(20) { Session.input(JSONObject().put("op", "pointer").put("dx", 10).put("dy", 6)); Thread.sleep(30) }
        step("pointer_moved")
        Thread.sleep(1500)
        val v = Session.run("connect", "media", JSONObject().put("action", "volume_down"))
        result("volume", v.optJSONObject("data")?.optInt("volume"))
        assertTrue(v.optBoolean("ok"))
    }

    @Test fun t09_screen() = runBlocking<Unit> {
        step("glance")
        val shot = Session.bytes("/v1/screen/shot")
        val bmp = BitmapFactory.decodeByteArray(shot, 0, shot.size)
        result("glance", "${bmp.width}x${bmp.height}, ${shot.size} bytes")
        assertTrue(bmp.width > 100)
        step("second_screen")
        ActivityScenario.launch<StreamActivity>(StreamActivity.intent(ctx, "virtual", 0))
        // KDE sends a frame when the screen changes, so a still desktop gives about one frame a second:
        // decoded frames and a measured delay are what count
        until(150, "frames on the second screen") { (StreamActivity.current?.framesShown ?: 0) >= 3 && (StreamActivity.current?.lastLatency ?: -1) >= 0 }
        Thread.sleep(5000)
        result("latency_ms", StreamActivity.current?.lastLatency)
        result("frames", StreamActivity.current?.framesShown)
        step("stream_tap")
        InstrumentationRegistry.getInstrumentation().uiAutomation.let {
            val d = InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics
            device.click(d.widthPixels / 2, d.heightPixels / 2)
        }
        Thread.sleep(2000)
        step("stream_tapped")
        StreamActivity.current?.finish()
        Thread.sleep(1500)
    }

    @Test fun t10_alerts_and_aira_confirm() {
        main()
        val gotNote = CompletableDeferred<JSONObject>()
        val job = Session.scope.launch { gotNote.complete(Session.events.first { it.optString("t") == "notification" && it.optString("app") == "AIRA" }) }
        val before = Session.transcript.value.count { it.who == "aira" }
        step("reminder_asked")
        Session.say("Remind me in 1 minute to check the training run")
        until(300, "AIRA's reply to the reminder") { Session.transcript.value.count { it.who == "aira" } > before }
        result("reminder_reply", Session.transcript.value.last { it.who == "aira" }.text.replace("\n", " ").take(160))
        val note = runBlocking { withTimeoutOrNull(240_000) { gotNote.await() } }
        job.cancel()
        result("notification", note?.let { "${it.optString("title")}: ${it.optString("body")}" })
        assertNotNull(note)
        // AIRA's own confirmation, answered on the phone
        tap("tab_aira")
        Session.say("Delete the file ~/Documents/e2e-delete-me.txt")
        assertNotNull("AIRA's confirmation", device.wait(Until.findObject(By.res("confirmText")), 300_000))
        result("aira_asked", Session.confirms.value.firstOrNull()?.summary)
        tap("confirmYes")
        // deleting is a "strong" approval: the phone's screen lock (PIN) is asked, as for the user
        Thread.sleep(2500)
        val sh = InstrumentationRegistry.getInstrumentation().uiAutomation
        sh.executeShellCommand("input text ${args.getString("pin") ?: "2468"}").close()
        Thread.sleep(500)
        sh.executeShellCommand("input keyevent 66").close()
        until(240, "AIRA's reply") { !Session.busy.value && Session.transcript.value.lastOrNull()?.who == "aira" }
        result("aira_confirm_reply", Session.transcript.value.last().text.replace("\n", " ").take(160))
        step("aira_confirmed")
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object { const val TAG = "AIROXE2E" }
}
