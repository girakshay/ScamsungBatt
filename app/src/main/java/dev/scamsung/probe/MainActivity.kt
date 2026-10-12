package dev.scamsung.probe

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Probe tools: find which Settings key (if any) drives something in the One UI status bar.
 * Workflow: Snapshot A -> change an option in Settings -> Snapshot B -> Diff A vs B.
 * Writes need WRITE_SECURE_SETTINGS:
 *   adb shell pm grant dev.scamsung.probe android.permission.WRITE_SECURE_SETTINGS
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ControlTheme { ProbeScreen() } }
    }
}

private val NAMESPACES = listOf("system", "secure", "global")

// Keys worth reading: the ones that exist on One UI 9 plus earlier guesses. Not confirmed to do anything.
private val CANDIDATES = listOf(
    "system" to "display_battery_percentage",
    "system" to "show_battery_background",
    "system" to "status_bar_show_battery_percent",
    "secure" to "status_bar_show_battery_percent",
    "system" to "show_battery_percent",
    "secure" to "show_battery_percent",
    "global" to "show_battery_percent",
    "system" to "battery_percentage",
    "secure" to "battery_percentage",
    "system" to "battery_percent",
    "secure" to "battery_percent",
    "secure" to "quick_panel_percent_text",
    "secure" to "icon_blacklist",
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProbeScreen() {
    val ctx = LocalContext.current
    var out by remember { mutableStateOf(statusText(ctx)) }
    var ns by remember { mutableStateOf(NAMESPACES[0]) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Probe tools", style = MaterialTheme.typography.displaySmallEmphasized, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Snapshot A, change an option in Settings, Snapshot B, then diff to find the settings key behind it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Snapshots") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ out = snap(ctx, "A") }, Modifier.weight(1f)) { Text("Snapshot A") }
                        FilledTonalButton({ out = snap(ctx, "B") }, Modifier.weight(1f)) { Text("Snapshot B") }
                    }
                    ExpressiveButton("Diff A vs B") { out = diff(ctx) }
                    ExpressiveButton("Search for battery / percent", tonal = true) { out = search(ctx.contentResolver) }
                    ExpressiveButton("Read candidate keys", tonal = true) { out = candidates(ctx.contentResolver) }
                    ExpressiveButton("Permission status", tonal = true) { out = statusText(ctx) }
                }
            }

            Section("Write a setting") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        NAMESPACES.forEachIndexed { i, n ->
                            SegmentedButton(
                                selected = ns == n,
                                onClick = { ns = n },
                                shape = SegmentedButtonDefaults.itemShape(i, NAMESPACES.size),
                            ) { Text(n) }
                        }
                    }
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("key") }, singleLine = true)
                    OutlinedTextField(
                        value, { value = it }, Modifier.fillMaxWidth(),
                        label = { Text("value (empty = delete)") }, singleLine = true,
                    )
                    ExpressiveButton("Put key / value") { out = put(ctx, ns, key.trim(), value.trim()) }
                }
            }

            Section("Output") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                out,
                                Modifier.padding(16.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    ExpressiveButton("Share output", tonal = true) {
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out),
                                "Share",
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- probe logic ----

private fun granted(ctx: Context) =
    ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

private fun statusText(ctx: Context) =
    "WRITE_SECURE_SETTINGS: ${if (granted(ctx)) "GRANTED" else "NOT granted"}\n" +
        (if (granted(ctx)) "" else "Run on PC:\nadb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS\n")

private fun table(cr: ContentResolver, ns: String): Map<String, String?> {
    val uri = when (ns) {
        "system" -> Settings.System.CONTENT_URI
        "secure" -> Settings.Secure.CONTENT_URI
        else -> Settings.Global.CONTENT_URI
    }
    val m = sortedMapOf<String, String?>()
    cr.query(uri, arrayOf("name", "value"), null, null, null)?.use {
        while (it.moveToNext()) m["$ns/${it.getString(0)}"] = it.getString(1)
    }
    return m
}

private fun dump(cr: ContentResolver): Map<String, String?> = NAMESPACES.fold(emptyMap()) { acc, ns -> acc + table(cr, ns) }

private fun snap(ctx: Context, tag: String): String {
    val d = dump(ctx.contentResolver)
    File(ctx.filesDir, "snap$tag.txt").writeText(d.entries.joinToString("\n") { "${it.key}=${it.value}" })
    return "Snapshot $tag saved: ${d.size} entries"
}

private fun load(ctx: Context, tag: String): Map<String, String> =
    File(ctx.filesDir, "snap$tag.txt").takeIf { it.exists() }?.readLines()?.associate {
        it.substringBefore("=") to it.substringAfter("=", "")
    } ?: emptyMap()

private fun diff(ctx: Context): String {
    val a = load(ctx, "A"); val b = load(ctx, "B")
    if (a.isEmpty() || b.isEmpty()) return "Take both snapshots first."
    val lines = (a.keys + b.keys).sorted().filter { a[it] != b[it] }
        .joinToString("\n") { "$it: ${a[it] ?: "<none>"} -> ${b[it] ?: "<none>"}" }
    return lines.ifEmpty { "No differences." }
}

private fun search(cr: ContentResolver): String {
    val re = Regex("batt|percent|pill|statusbar|status_bar|icon_blacklist", RegexOption.IGNORE_CASE)
    return dump(cr).filter { re.containsMatchIn(it.key) }.entries
        .joinToString("\n") { "${it.key}=${it.value}" }.ifEmpty { "No matches." }
}

private fun read(cr: ContentResolver, ns: String, k: String): String? = when (ns) {
    "system" -> Settings.System.getString(cr, k)
    "secure" -> Settings.Secure.getString(cr, k)
    else -> Settings.Global.getString(cr, k)
}

private fun candidates(cr: ContentResolver) =
    CANDIDATES.joinToString("\n") { (ns, k) -> "$ns/$k = ${read(cr, ns, k) ?: "<not set>"}" }

private fun put(ctx: Context, ns: String, k: String, v: String): String {
    if (k.isEmpty()) return "Enter a key."
    if (!granted(ctx) && ns != "system") return "Need WRITE_SECURE_SETTINGS for $ns."
    val cr = ctx.contentResolver
    return try {
        val old = read(cr, ns, k)
        val nv = v.ifEmpty { null }
        when (ns) {
            "system" -> Settings.System.putString(cr, k, nv)
            "secure" -> Settings.Secure.putString(cr, k, nv)
            else -> Settings.Global.putString(cr, k, nv)
        }
        "$ns/$k: ${old ?: "<none>"} -> ${read(cr, ns, k) ?: "<none>"}\nRevert by putting the old value back."
    } catch (e: Exception) {
        "Failed: $e"
    }
}
