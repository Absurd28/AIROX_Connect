package org.airux.connect.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.airux.connect.media.Recorder
import org.airux.connect.net.Msg
import org.airux.connect.net.Session
import org.airux.connect.ui.theme.Ax
import org.airux.connect.ui.theme.Muted

@Composable
fun AiraScreen() {
    val ctx = LocalContext.current
    val list by Session.transcript.collectAsState()
    val busy by Session.busy.collectAsState()
    val airaUp by Session.airaUp.collectAsState()
    var text by remember { mutableStateOf("") }
    val rec = remember { Recorder() }
    var recording by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast(ctx, "AIROX Connect needs the microphone for voice commands.")
    }
    LaunchedEffect(list.size) { if (list.isNotEmpty()) state.animateScrollToItem(list.size - 1) }

    fun toggleMic() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (!recording) {
            try { rec.start(); recording = true } catch (e: Exception) { toast(ctx, e.message ?: "microphone error") }
        } else {
            recording = false
            val wav = rec.stop()
            Session.markVoice()
            Session.scope.launch {
                try { Session.post("/v1/aira/voice", wav, "audio/wav") } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { toast(ctx, e.message ?: "not sent") }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (!airaUp) Muted("AIRA isn't running on the PC right now.", Modifier.padding(4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("transcript"), state = state,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) item {
                Muted("Ask AIRA anything you would ask her at the PC: \"how much disk space do I have\", " +
                    "\"open the browser and search for qwen\", \"remind me at 6 to call mum\".", Modifier.padding(12.dp), 14)
            }
            items(list, key = { "${it.ts}-${it.who}-${it.text.hashCode()}" }) { Bubble(it) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Ax.blue)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).testTag("sayText"),
                placeholder = { Text(if (recording) "Listening… tap stop when done" else "Message AIRA") }, maxLines = 4)
            Spacer(Modifier.size(8.dp))
            if (text.isBlank()) {
                FilledIconButton(onClick = ::toggleMic, Modifier.size(52.dp).testTag("mic"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording) Ax.red else Ax.blue)) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, "Talk")
                }
            } else {
                FilledIconButton(onClick = {
                    if (!Session.say(text.trim())) toast(ctx, "Not connected to the PC") else text = ""
                }, Modifier.size(52.dp).testTag("send"), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ax.blue)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg) {
    val mine = m.who == "you"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).background(
                when (m.who) { "you" -> Ax.line; "error" -> Ax.red.copy(alpha = 0.15f); else -> Ax.panel }, RoundedCornerShape(14.dp),
            ).padding(12.dp),
        ) {
            Text(m.text, fontSize = 15.sp, modifier = if (m.who == "aira") Modifier.testTag("airaReply") else Modifier)
            if (m.tags.isNotEmpty() || (mine && m.via.isNotEmpty())) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mine && m.via.isNotEmpty()) Muted("via ${m.via}", size = 11)
                    m.tags.forEach { t ->
                        Text(t, fontSize = 11.sp, color = when (t) { "verified", "cited" -> Ax.green; "not sure", "unverified", "not done" -> Ax.amber; else -> Ax.muted },
                            modifier = Modifier.background(Ax.bg, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}
