package org.airux.connect

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.airux.connect.net.Conn
import org.airux.connect.net.Session
import org.airux.connect.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A "strong" approval (an administrator action) is confirmed on the phone with its screen lock.
 *  Needs a paired PC and a PIN on the device (argument `pin`). Restarts the PC's `cron` service. */
@RunWith(AndroidJUnit4::class)
class StrongConfirm {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)

    @Test fun strongApprovalNeedsTheScreenLock() = runBlocking<Unit> {
        val pin = InstrumentationRegistry.getArguments().getString("pin") ?: "2468"
        Session.start()
        val end = System.currentTimeMillis() + 90_000
        while (Session.conn.value !is Conn.Online && System.currentTimeMillis() < end) Thread.sleep(250)
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val r = Session.scope.async {
            Session.run("taskmgr", "service", JSONObject().put("name", "cron.service").put("action", "restart").put("scope", "system"))
        }
        val yes = device.wait(Until.findObject(By.res("confirmYes")), 60_000)
        assertNotNull("confirmation shown", yes)
        yes.click()
        Thread.sleep(2500)
        val shot = File(inst.targetContext.getExternalFilesDir(null), "strong-prompt.png")
        device.takeScreenshot(shot)
        Log.i("AIROXE2E", "RESULT prompt_screenshot=${shot.absolutePath}")
        inst.uiAutomation.executeShellCommand("input text $pin").close()
        Thread.sleep(500)
        inst.uiAutomation.executeShellCommand("input keyevent 66").close()
        val out = r.await()
        Log.i("AIROXE2E", "RESULT strong=$out")
        assertTrue(out.toString(), out.optBoolean("allowed"))
    }

    @Test fun airaDeleteApprovedWithPin() = runBlocking<Unit> {
        val pin = InstrumentationRegistry.getArguments().getString("pin") ?: "2468"
        Session.start()
        val end = System.currentTimeMillis() + 90_000
        while (Session.conn.value !is Conn.Online && System.currentTimeMillis() < end) Thread.sleep(250)
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val job = Session.scope.launch { Session.events.collect { e ->
            if (e.optString("t") == "aira" && e.optString("event") in setOf("confirm", "confirm_done", "aira"))
                seen.add("${System.currentTimeMillis() % 100000} ${e.optString("event")} ${e.optString("id")} ${e.optString("why")} ${e.optString("text").take(80)}") } }
        Session.say("Delete the file ~/Documents/e2e-delete-me.txt")
        val yes = device.wait(Until.findObject(By.res("confirmYes")), 300_000)
        assertNotNull("AIRA's confirmation shown", yes)
        Log.i("AIROXE2E", "RESULT asked=${Session.confirms.value.map { it.id + ":" + it.src + ":" + it.strong }}")
        yes.click()
        Thread.sleep(2500)
        inst.uiAutomation.executeShellCommand("input text $pin").close()
        Thread.sleep(500)
        inst.uiAutomation.executeShellCommand("input keyevent 66").close()
        val stop = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < stop && seen.none { it.contains(" aira ") }) Thread.sleep(500)
        job.cancel()
        seen.forEach { Log.i("AIROXE2E", "RESULT event=$it") }
    }
}
