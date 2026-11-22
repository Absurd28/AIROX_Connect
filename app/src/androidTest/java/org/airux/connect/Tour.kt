package org.airux.connect

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.airux.connect.net.Conn
import org.airux.connect.net.Session
import org.airux.connect.ui.MainActivity
import org.airux.connect.ui.StreamActivity
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A screenshot tour for the README, against a real AIRUX PC (AIRUX's VM; someone at the PC allows the
 *  pairing and KDE's windows). Arguments: pairUri. Screenshots: Android/data/org.airux.connect/files/tour/. */
@RunWith(AndroidJUnit4::class)
class Tour {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val ctx = inst.targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "tour").apply { mkdirs() }

    private fun step(s: String) = Log.i("AIROXE2E", "STEP $s")
    private fun shot(name: String) {
        device.waitForIdle(2000)
        device.takeScreenshot(File(dir, "$name.png"))
        Log.i("AIROXE2E", "RESULT shot=$name")
    }
    private fun tap(tag: String, seconds: Long = 30) =
        (device.wait(Until.findObject(By.res(tag)), seconds * 1000) ?: throw AssertionError("no '$tag'")).click()
    private fun until(seconds: Int, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end && !(try { cond() } catch (e: Exception) { false })) Thread.sleep(250)
    }

    @Test fun tour() = runBlocking<Unit> {
        Session.unpair()
        ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.findObject(By.res("scan")), 20_000)
        shot("01-pair")
        step("pair_waiting")
        ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_VIEW, Uri.parse(InstrumentationRegistry.getArguments().getString("pairUri")))
            .setClass(ctx, MainActivity::class.java))
        device.wait(Until.findObject(By.res("pcName")), 150_000)
        until(30) { Session.conn.value is Conn.Online }
        Thread.sleep(7000)
        shot("02-home")

        tap("tab_aira")
        val before = Session.transcript.value.count { it.who == "aira" }
        Session.say("How much free disk space do I have in my home folder?")
        until(300) { Session.transcript.value.count { it.who == "aira" } > before && !Session.busy.value }
        Thread.sleep(2000)
        shot("03-aira")

        tap("tab_tasks")
        Thread.sleep(5000)
        shot("04-tasks-processes")
        tap("tasks_Performance")
        Thread.sleep(9000)
        shot("05-tasks-performance")
        // an approval: ending a program asks on the phone first (answered "No" here)
        val p = Session.get("/v1/tasks/processes").getJSONArray("groups").let { gs ->
            (0 until gs.length()).flatMap { i -> gs.getJSONObject(i).getJSONArray("procs").let { ps -> (0 until ps.length()).map { ps.getJSONObject(it) } } }
        }.firstOrNull { it.optString("cmdline").contains("sleep 4242") }
        if (p != null) {
            val r = Session.scope.async { Session.run("taskmgr", "end_task", JSONObject().put("pid", p.optInt("pid")).put("start", p.optLong("start"))) }
            device.wait(Until.findObject(By.res("confirmText")), 30_000)
            Thread.sleep(800)
            shot("06-approval")
            tap("confirmNo")
            r.await()
        }

        tap("tab_files")
        Thread.sleep(3000)
        device.wait(Until.findObject(By.res("file_Pictures")), 10_000)?.click()
        Thread.sleep(3000)
        shot("07-files")

        tap("tab_more")
        Thread.sleep(1500)
        shot("08-more")
        tap("more_pad")
        step("input_start")
        Session.input(JSONObject().put("op", "start"))
        until(120) { Session.inputState.value == "ready" }
        Thread.sleep(1500)
        shot("09-touchpad")
        device.pressBack()
        tap("more_screen")
        tap("glance")
        device.wait(Until.findObject(By.res("glanceImage")), 90_000)
        Thread.sleep(1500)
        shot("10-glance")

        step("second_screen")
        ActivityScenario.launch<StreamActivity>(StreamActivity.intent(ctx, "virtual", 0))
        device.wait(Until.findObject(By.text("Got it")), 20_000)?.click()      // Android's one-time full-screen hint
        until(150) { (StreamActivity.current?.lastLatency ?: -1) >= 0 }
        step("move_window")                                                   // the PC puts a window on the new screen
        Thread.sleep(12000)
        shot("11-second-screen")
        StreamActivity.current?.finish()
        Thread.sleep(1500)
        step("done")
    }

    /** Only the second screen, with a window the PC has moved onto it (step "move_window"). */
    @Test fun secondScreen() = runBlocking<Unit> {
        Session.start()
        until(90) { Session.conn.value is Conn.Online }
        step("second_screen")
        ActivityScenario.launch<StreamActivity>(StreamActivity.intent(ctx, "virtual", 0))
        device.wait(Until.findObject(By.text("Got it")), 15_000)?.click()
        until(150) { (StreamActivity.current?.lastLatency ?: -1) >= 0 }
        step("move_window")
        Thread.sleep(25_000)
        shot("11-second-screen")
        StreamActivity.current?.finish()
    }

    /** Only the Task Manager's Performance tab. */
    @Test fun performance() = runBlocking<Unit> {
        Session.start()
        until(90) { Session.conn.value is Conn.Online }
        ActivityScenario.launch(MainActivity::class.java)
        tap("tab_tasks")
        tap("tasks_Performance")
        Thread.sleep(12_000)
        shot("05-tasks-performance")
    }
}
