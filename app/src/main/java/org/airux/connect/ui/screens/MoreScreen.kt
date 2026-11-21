package org.airux.connect.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.airux.connect.net.Lan
import org.airux.connect.net.Session
import org.airux.connect.service.ConnectService
import org.airux.connect.ui.StreamActivity
import org.airux.connect.ui.theme.Ax
import org.airux.connect.ui.theme.Card
import org.airux.connect.ui.theme.Muted
import org.airux.connect.ui.theme.SectionTitle
import org.json.JSONObject
import kotlin.math.abs

private data class Tile(val id: String, val title: String, val sub: String, val icon: ImageVector)

private val TILES = listOf(
    Tile("clip", "Clipboard & links", "Copy between phone and PC, open links on the PC", Icons.Filled.ContentPaste),
    Tile("pad", "Touchpad & media", "Mouse, keyboard, music and volume", Icons.Filled.Mouse),
    Tile("screen", "Screen", "Glance at the PC, second screen, mirror", Icons.Filled.Monitor),
    Tile("power", "Power", "Lock, sleep, restart, shut down, wake", Icons.Filled.PowerSettingsNew),
    Tile("settings", "Settings", "This phone, alerts, unpair", Icons.Filled.Settings),
)

@Composable
fun MoreScreen() {
    var page by remember { mutableStateOf<String?>(null) }
    BackHandler(page != null) { page = null }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val p = page
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(TILES.first { it.id == p }.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (p) {
                null -> TILES.forEach { t ->
                    Row(Modifier.fillMaxWidth().background(Ax.panel, RoundedCornerShape(16.dp)).border(1.dp, Ax.line, RoundedCornerShape(16.dp))
                        .clickable { page = t.id }.padding(16.dp).testTag("more_${t.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, tint = Ax.blue, modifier = Modifier.size(28.dp))
                        Column(Modifier.padding(start = 14.dp)) { Text(t.title, fontWeight = FontWeight.SemiBold); Muted(t.sub) }
                    }
                }
                "clip" -> ClipboardPage()
                "pad" -> TouchpadPage()
                "screen" -> ScreenPage()
                "power" -> PowerPage()
                "settings" -> SettingsPage()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ------------------------------------------------------------------ clipboard and links
@Composable
private fun ClipboardPage() {
    val ctx = LocalContext.current
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var pcText by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }
    Card {
        SectionTitle("PC → phone")
        Button({
            Session.scope.launch {
                val t = try { Session.get("/v1/clipboard").optJSONObject("data")?.optString("text") } catch (e: Exception) { null }
                withContext(Dispatchers.Main) {
                    if (t == null) toast(ctx, "Could not read the PC's clipboard") else {
                        pcText = t
                        cm.setPrimaryClip(ClipData.newPlainText("From PC", t))
                        toast(ctx, "Copied the PC's clipboard to this phone")
                    }
                }
            }
        }, modifier = Modifier.testTag("clipGet")) { Text("Get the PC's clipboard") }
        pcText?.let { Muted(it.take(500), Modifier.padding(top = 8.dp).testTag("clipText")) }
    }
    Card {
        SectionTitle("Phone → PC")
        Button({
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (t.isNullOrEmpty()) toast(ctx, "This phone's clipboard is empty")
            else act(ctx, "connect", "clipboard_set", JSONObject().put("text", t))
        }, modifier = Modifier.testTag("clipSend")) { Text("Send this phone's clipboard") }
    }
    Card {
        SectionTitle("Open a link on the PC")
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth().testTag("linkText"), singleLine = true, placeholder = { Text("https://…") })
        Button({ act(ctx, "connect", "open_link", JSONObject().put("url", link.trim())) }, Modifier.padding(top = 8.dp)) { Text("Open in NEOX") }
        Muted("Tip: in any app, Share → \"Send to PC (AIROX Connect)\" sends links and files.", Modifier.padding(top = 6.dp), 12)
    }
}

// ------------------------------------------------------------------ touchpad, keyboard and media
@Composable
private fun TouchpadPage() {
    val ctx = LocalContext.current
    val input by Session.inputState.collectAsState()
    var typed by remember { mutableStateOf("") }
    if (input != "ready") {
        Card {
            SectionTitle("Use this phone as touchpad")
            Muted(when (input) {
                "denied" -> "The PC did not allow it. Try again and click Share on the PC."
                "error" -> "Remote input stopped. Try again."
                else -> "The first time, KDE on the PC asks to allow remote control. Click Share there; it is remembered."
            })
            Button({ Session.input(JSONObject().put("op", "start")) }, Modifier.padding(top = 8.dp).testTag("padStart")) { Text("Start") }
        }
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f).background(Ax.panel, RoundedCornerShape(16.dp)).border(1.dp, Ax.line, RoundedCornerShape(16.dp))
            .testTag("touchpad").pointerInput(Unit) { touchpad() }) {
            Muted("Move with one finger · tap to click · two-finger tap for right click · two fingers to scroll",
                Modifier.align(Alignment.Center).padding(24.dp), 12)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ click("left") }, Modifier.weight(1f)) { Text("Left") }
            OutlinedButton({ click("right") }, Modifier.weight(1f)) { Text("Right") }
        }
        Card {
            SectionTitle("Keyboard")
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), placeholder = { Text("Type here, then Send") })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ Session.input(JSONObject().put("op", "text").put("text", typed)); typed = "" }) { Text("Send") }
                FilledTonalIconButton({ key(0xFF0D) }) { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter") }
                FilledTonalIconButton({ key(0xFF08) }) { Icon(Icons.Filled.Backspace, "Backspace") }
                OutlinedButton({ key(0xFF1B) }) { Text("Esc") }
                OutlinedButton({ key(0xFF09) }) { Text("Tab") }
            }
        }
    }
    Card {
        SectionTitle("Media")
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            FilledTonalIconButton({ media(ctx, "previous") }) { Icon(Icons.Filled.SkipPrevious, "Previous") }
            FilledTonalIconButton({ media(ctx, "play_pause") }, Modifier.testTag("playPause")) { Icon(Icons.Filled.PlayArrow, "Play or pause") }
            FilledTonalIconButton({ media(ctx, "next") }) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            FilledTonalIconButton({ media(ctx, "volume_down") }, Modifier.testTag("volDown")) { Icon(Icons.AutoMirrored.Filled.VolumeDown, "Volume down") }
            FilledTonalIconButton({ media(ctx, "mute") }) { Icon(Icons.AutoMirrored.Filled.VolumeOff, "Mute") }
            FilledTonalIconButton({ media(ctx, "volume_up") }, Modifier.testTag("volUp")) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume up") }
        }
    }
}

private fun media(ctx: Context, a: String) = act(ctx, "connect", "media", JSONObject().put("action", a), quiet = true) { r ->
    r.optJSONObject("data")?.let { d -> if (d.has("volume")) toast(ctx, "Volume ${d.optInt("volume")}%${if (d.optBoolean("muted")) " (muted)" else ""}") }
}

private fun click(b: String) {
    Session.input(JSONObject().put("op", "button").put("button", b).put("down", true))
    Session.input(JSONObject().put("op", "button").put("button", b).put("down", false))
}

private fun key(sym: Int) {
    Session.input(JSONObject().put("op", "key").put("keysym", sym).put("down", true))
    Session.input(JSONObject().put("op", "key").put("keysym", sym).put("down", false))
}

/** One finger moves (with a little acceleration), a tap clicks, two fingers scroll, a two-finger tap right-clicks. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.touchpad() {
    awaitEachGesture {
        val down = awaitFirstDown()
        val t0 = System.currentTimeMillis()
        var moved = 0f
        var maxFingers = 1
        var last = down.position
        var lastMid: androidx.compose.ui.geometry.Offset? = null
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            maxFingers = maxOf(maxFingers, pressed.size)
            if (pressed.size == 1) {
                val p = pressed[0].position
                val dx = p.x - last.x; val dy = p.y - last.y
                last = p
                lastMid = null
                val speed = kotlin.math.sqrt(dx * dx + dy * dy)
                val gain = 1.1f + (speed / 25f).coerceAtMost(2.5f)
                if (speed > 0.5f) {
                    moved += speed
                    Session.input(JSONObject().put("op", "pointer").put("dx", (dx * gain).toDouble()).put("dy", (dy * gain).toDouble()))
                }
            } else {
                val mid = androidx.compose.ui.geometry.Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat())
                lastMid?.let { lm ->
                    val dy = mid.y - lm.y
                    if (abs(dy) > 2f) { moved += abs(dy); Session.input(JSONObject().put("op", "scroll").put("dx", 0).put("dy", (-dy / 4).toDouble())) }
                }
                lastMid = mid
            }
            ev.changes.forEach { it.consume() }
        }
        if (moved < 12f && System.currentTimeMillis() - t0 < 300) click(if (maxFingers >= 2) "right" else "left")
    }
}

// ------------------------------------------------------------------ screen
@Composable
private fun ScreenPage() {
    val ctx = LocalContext.current
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(Session.store.screenScale) }
    Card {
        SectionTitle("Glance at the PC")
        Button({
            loading = true
            Session.scope.launch {
                val bmp = try { Session.bytes("/v1/screen/shot").let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (e: Exception) { null }
                withContext(Dispatchers.Main) { loading = false; if (bmp == null) toast(ctx, "Could not capture the PC's screen") else shot = bmp.asImageBitmap() }
            }
        }, enabled = !loading, modifier = Modifier.testTag("glance")) { Text(if (loading) "Capturing…" else "Take a look") }
        shot?.let { Image(it, "The PC's screen", Modifier.fillMaxWidth().padding(top = 10.dp).testTag("glanceImage")) }
    }
    Card {
        SectionTitle("Second screen")
        Muted("This phone or tablet becomes another monitor of the PC (extend the desktop onto it), with touch. " +
            "The first time, choose \"New Virtual Output\" in the window KDE shows on the PC.")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            listOf("Balanced", "Sharp", "Fast").forEachIndexed { i, l ->
                SegmentedButton(quality == i, { quality = i; Session.store.screenScale = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ ctx.startActivity(StreamActivity.intent(ctx, "virtual", quality)) }, Modifier.testTag("secondScreen"),
                colors = ButtonDefaults.buttonColors(containerColor = Ax.blue)) { Text("Use as second screen") }
            OutlinedButton({ ctx.startActivity(StreamActivity.intent(ctx, "monitor", quality)) }) { Text("Mirror") }
        }
        Muted("Lowest delay on 5 GHz Wi-Fi with the phone near the router. The delay from the PC to this screen is shown while it runs.",
            Modifier.padding(top = 6.dp), 12)
    }
}

// ------------------------------------------------------------------ power
@Composable
private fun PowerPage() {
    val ctx = LocalContext.current
    val p by Session.paired.collectAsState()
    Card {
        SectionTitle("Your PC")
        listOf(Triple("lock", "Lock", Icons.Filled.Lock), Triple("sleep", "Sleep", Icons.Filled.Bedtime),
            Triple("restart", "Restart", Icons.Filled.RestartAlt), Triple("shutdown", "Shut down", Icons.Filled.PowerSettingsNew)).forEach { (a, l, i) ->
            OutlinedButton({ act(ctx, "connect", "power", JSONObject().put("action", a)) }, Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("power_$a")) {
                Icon(i, null); Spacer(Modifier.size(8.dp)); Text(l)
            }
        }
        Muted("Sleep, restart and shut down ask you first.", Modifier.padding(top = 4.dp), 12)
    }
    Card {
        SectionTitle("Wake the PC")
        Muted(if (p?.macs.isNullOrEmpty()) "The PC did not report a network card for Wake-on-LAN."
              else "Sends a Wake-on-LAN packet on this network. Works when the PC is asleep and its network card allows it (usually Ethernet).")
        Button({
            val macs = p?.macs.orEmpty()
            Session.scope.launch {
                val n = try { Lan.wake(macs) } catch (e: Exception) { 0 }
                withContext(Dispatchers.Main) { toast(ctx, if (n > 0) "Wake-up sent" else "Could not send the wake-up") }
                kotlinx.coroutines.delay(8000)
                Session.reconnectNow()
            }
        }, enabled = !p?.macs.isNullOrEmpty(), modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.WbSunny, null); Spacer(Modifier.size(8.dp)); Text("Wake up") }
    }
}

// ------------------------------------------------------------------ settings
@Composable
private fun SettingsPage() {
    val ctx = LocalContext.current
    val p by Session.paired.collectAsState()
    var stay by remember { mutableStateOf(Session.store.stayConnected) }
    var reply by remember { mutableStateOf(Session.store.replyOnPhone) }
    var alerts by remember { mutableStateOf(Session.store.pcAlerts) }
    Card {
        SectionTitle("Paired PC")
        Text(p?.pcName ?: "—")
        Muted("Certificate ${p?.fingerprint?.take(24)?.chunked(4)?.joinToString(" ")}…", size = 12)
        Muted("Addresses ${p?.hosts?.joinToString(", ")} · port ${p?.port}", size = 12)
    }
    Card {
        Toggle("Stay connected in the background", "Alerts, approvals, files from the PC and \"ring my phone\" arrive when the app is closed.", stay) {
            stay = it; Session.store.stayConnected = it
            if (it) ConnectService.start(ctx) else ConnectService.stop(ctx)
        }
        Toggle("AIRA answers spoken questions here", "Play AIRA's voice on this phone after you ask by voice.", reply) { reply = it; Session.store.replyOnPhone = it }
        Toggle("PC alerts", "Show AIRA reminders, finished jobs and Phagos warnings as notifications.", alerts) { alerts = it; Session.store.pcAlerts = it }
    }
    Card {
        SectionTitle("About")
        Muted("AIROX Connect 0.1 for AIRUX. AIRA is the Adaptive Intelligent Research Assistant. Nothing leaves your local network.")
    }
    OutlinedButton({ Session.unpair(); ConnectService.stop(ctx) }, Modifier.fillMaxWidth().testTag("unpair"),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ax.red)) { Text("Unpair this phone") }
    Muted("To remove this phone completely, also remove it in AIROX Connect on the PC.", size = 12)
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Muted(sub, size = 12) }
        Switch(on, set)
    }
}
