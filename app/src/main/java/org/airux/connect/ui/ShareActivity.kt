package org.airux.connect.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import org.airux.connect.net.Conn
import org.airux.connect.net.Session
import org.airux.connect.transfer.Transfers
import org.airux.connect.ui.screens.act
import org.airux.connect.ui.theme.AiroxTheme
import org.airux.connect.ui.theme.Ax
import org.airux.connect.ui.theme.Card
import org.airux.connect.ui.theme.Muted
import org.json.JSONObject

/** Android's share sheet -> "Send to PC": files and media are uploaded; a link can be opened on the PC
 *  or put on its clipboard. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.start()
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        setContent { AiroxTheme { Share(uris, text) } }
    }

    @Composable
    private fun Share(uris: List<Uri>, text: String?) {
        val conn by Session.conn.collectAsState()
        val paired by Session.paired.collectAsState()
        val transfers by Transfers.list.collectAsState()
        var started by remember { mutableStateOf(false) }
        val link = text?.let { Regex("https?://\\S+").find(it)?.value }
        Column(Modifier.fillMaxSize().background(Ax.bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Send to ${paired?.pcName ?: "your PC"}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (paired == null) { Muted("Pair this phone with your PC first (open AIROX Connect)."); return@Column }
            if (conn !is Conn.Online) Muted("Connecting to the PC…")
            if (uris.isNotEmpty()) Card {
                Text("${uris.size} file(s)")
                Muted("They are saved on the PC in Downloads/AIROX Connect. Nothing is overwritten.")
                if (!started) Button({ started = true; uris.forEach { Transfers.upload(this@ShareActivity, it) } }, Modifier.padding(top = 8.dp),
                    enabled = conn is Conn.Online) { Text("Send") }
                transfers.filter { it.up }.takeLast(uris.size).forEach { t ->
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(t.name, fontSize = 14.sp)
                        when (t.state) {
                            "done" -> Muted("Sent", size = 12)
                            "failed" -> Text(t.error ?: "failed", color = Ax.red, fontSize = 12.sp)
                            else -> LinearProgressIndicator(progress = { if (t.size > 0) t.done.toFloat() / t.size else 0f }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (text != null && uris.isEmpty()) Card {
                Muted(text.take(300))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link != null) Button({ act(this@ShareActivity, "connect", "open_link", JSONObject().put("url", link)) },
                        enabled = conn is Conn.Online) { Text("Open on the PC") }
                    OutlinedButton({ act(this@ShareActivity, "connect", "clipboard_set", JSONObject().put("text", text)) },
                        enabled = conn is Conn.Online) { Text("Copy to PC") }
                }
            }
            OutlinedButton({ finish() }) { Text("Close") }
        }
    }
}
