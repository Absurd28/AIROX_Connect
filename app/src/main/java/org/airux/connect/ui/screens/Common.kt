package org.airux.connect.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.airux.connect.net.Conn
import org.airux.connect.net.Session
import org.airux.connect.net.outcome
import org.json.JSONObject

/** Fetch something from the PC now and every `everyMs` while the screen is shown and the PC is connected. */
@Composable
fun <T> polled(everyMs: Long, key: Any? = null, fetch: suspend () -> T): State<T?> {
    val st = remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        while (true) {
            if (Session.conn.value is Conn.Online) {
                try { st.value = fetch() } catch (e: Exception) { }
            }
            delay(everyMs)
        }
    }
    return st
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/** Run a broker action and show its outcome; `then` gets the result when it worked. */
fun act(ctx: Context, scope: String, tool: String, args: JSONObject = JSONObject(), quiet: Boolean = false,
        then: (JSONObject) -> Unit = {}) {
    Session.scope.launch {
        val r = try { Session.run(scope, tool, args) } catch (e: Exception) {
            JSONObject().put("ok", false).put("allowed", true).put("error", e.message ?: "the PC did not answer")
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (!quiet || !r.optBoolean("ok")) toast(ctx, r.outcome())
            if (r.optBoolean("ok")) then(r)
        }
    }
}
