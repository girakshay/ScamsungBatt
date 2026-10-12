package dev.scamsung.probe

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * Probe: finds which Settings key (if any) drives the battery percentage in the One UI status bar.
 * The toggle applies the best-known hidden settings for "battery pill without percentage".
 * Workflow: Snapshot A -> change a status bar option in Settings -> Snapshot B -> Diff.
 * Also tries known/guessed keys and a free-form writer. Needs WRITE_SECURE_SETTINGS for writes:
 *   adb shell pm grant dev.scamsung.probe android.permission.WRITE_SECURE_SETTINGS
 */
class MainActivity : Activity() {
    private lateinit var out: TextView
    private val cr: ContentResolver get() = contentResolver
    private val prefs by lazy { getSharedPreferences("battery_pill_probe", MODE_PRIVATE) }

    private data class SettingCandidate(val ns: String, val key: String, val hideValue: String, val showValue: String = "1")

    // Guesses only; none confirmed on every One UI 8.5/9 build.
    private val percentCandidates = listOf(
        SettingCandidate("system", "status_bar_show_battery_percent", "0"),
        SettingCandidate("secure", "status_bar_show_battery_percent", "0"),
        SettingCandidate("system", "show_battery_percent", "0"),
        SettingCandidate("secure", "show_battery_percent", "0"),
        SettingCandidate("global", "show_battery_percent", "0"),
        SettingCandidate("system", "battery_percentage", "0"),
        SettingCandidate("secure", "battery_percentage", "0"),
        SettingCandidate("system", "battery_percent", "0"),
        SettingCandidate("secure", "battery_percent", "0"),
        SettingCandidate("system", "display_battery_percentage", "0"),
        SettingCandidate("secure", "display_battery_percentage", "0"),
    )

    private val backgroundCandidates = listOf(
        SettingCandidate("system", "show_battery_background", "1", "1"),
        SettingCandidate("secure", "show_battery_background", "1", "1"),
        SettingCandidate("system", "status_bar_show_battery_background", "1", "1"),
        SettingCandidate("secure", "status_bar_show_battery_background", "1", "1"),
        SettingCandidate("system", "battery_background", "1", "1"),
        SettingCandidate("secure", "battery_background", "1", "1"),
    )

    private val candidates = percentCandidates + backgroundCandidates

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun btn(label: String, f: () -> Unit) = root.addView(Button(this).apply { text = label; setOnClickListener { f() } })

        val ns = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("system", "secure", "global")) }
        val key = EditText(this).apply { hint = "key" }
        val value = EditText(this).apply { hint = "value (empty = delete)" }

        btn("Permission status") { status() }
        val removePercentage = Switch(this).apply {
            text = "Remove percentage from battery pill"
            gravity = Gravity.CENTER_VERTICAL
            isChecked = prefs.getBoolean("remove_percentage_enabled", false)
            setOnCheckedChangeListener { _, enabled -> setRemovePercentage(enabled) }
        }
        root.addView(removePercentage)
        btn("Restore saved battery settings") { restoreSavedSettings() }
        btn("Snapshot A (before)") { snap("A") }
        btn("Snapshot B (after)") { snap("B") }
        btn("Diff A vs B") { diff() }
        btn("Search dump for battery/percent") { search() }
        btn("Probe candidate keys (read)") { probe() }
        root.addView(ns); root.addView(key); root.addView(value)
        btn("Put key/value") { put(ns.selectedItem.toString(), key.text.toString().trim(), value.text.toString().trim()) }
        btn("Share output") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out.text.toString()), "Share"))
        }
        out = TextView(this).apply { setTextIsSelectable(true); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(out)
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }.also { sv ->
            sv.setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                i
            }
        })
        status()
    }

    private fun granted() = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun log(s: String) { out.text = s }

    private fun status() = log(
        "WRITE_SECURE_SETTINGS: ${if (granted()) "GRANTED" else "NOT granted"}\n" +
            (if (granted()) "" else "Run on PC:\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n") +
            "\nToggle: tries to hide percentage keys while keeping One UI 8.5/9 battery background keys enabled.\n" +
            "If Samsung removed the underlying setting on your build, the toggle may report changes without visual effect.\n" +
            "\nProbe steps: Snapshot A -> Settings > Notifications > Status bar, flip an option -> Snapshot B -> Diff."
    )

    private fun table(ns: String): Map<String, String?> {
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

    private fun dump(): Map<String, String?> = table("system") + table("secure") + table("global")

    private fun snap(tag: String) {
        val d = dump()
        File(filesDir, "snap$tag.txt").writeText(d.entries.joinToString("\n") { "${it.key}=${it.value}" })
        log("Snapshot $tag saved: ${d.size} entries")
    }

    private fun load(tag: String): Map<String, String> =
        File(filesDir, "snap$tag.txt").takeIf { it.exists() }?.readLines()?.associate {
            it.substringBefore("=") to it.substringAfter("=", "")
        } ?: emptyMap()

    private fun diff() {
        val a = load("A"); val c = load("B")
        if (a.isEmpty() || c.isEmpty()) return log("Take both snapshots first.")
        val sb = StringBuilder()
        (a.keys + c.keys).sorted().forEach { k ->
            if (a[k] != c[k]) sb.append("$k: ${a[k] ?: "<none>"} -> ${c[k] ?: "<none>"}\n")
        }
        log(if (sb.isEmpty()) "No differences." else sb.toString())
    }

    private fun search() {
        val re = Regex("batt|percent|pill|statusbar|status_bar|icon_blacklist", RegexOption.IGNORE_CASE)
        log(dump().filter { re.containsMatchIn(it.key) }.entries.joinToString("\n") { "${it.key}=${it.value}" }.ifEmpty { "No matches." })
    }

    private fun read(ns: String, k: String): String? = when (ns) {
        "system" -> Settings.System.getString(cr, k)
        "secure" -> Settings.Secure.getString(cr, k)
        else -> Settings.Global.getString(cr, k)
    }

    private fun probe() = log(candidates.joinToString("\n") { c -> "${c.ns}/${c.key} = ${read(c.ns, c.key) ?: "<not set>"}" })

    private fun put(ns: String, k: String, v: String) {
        if (k.isEmpty()) return log("Enter a key.")
        if (!granted() && ns != "system") return log("Need WRITE_SECURE_SETTINGS for $ns.")
        try {
            val old = read(ns, k)
            when (ns) {
                "system" -> Settings.System.putString(cr, k, v.ifEmpty { null })
                "secure" -> Settings.Secure.putString(cr, k, v.ifEmpty { null })
                else -> Settings.Global.putString(cr, k, v.ifEmpty { null })
            }
            log("$ns/$k: ${old ?: "<none>"} -> ${read(ns, k) ?: "<none>"}\nCheck status bar. Revert by putting the old value back.")
        } catch (e: Exception) {
            log("Failed: $e")
        }
    }

    private fun canWrite(ns: String) = ns == "system" || granted()

    private fun write(ns: String, k: String, v: String?): Boolean {
        if (!canWrite(ns)) return false
        return try {
            when (ns) {
                "system" -> Settings.System.putString(cr, k, v)
                "secure" -> Settings.Secure.putString(cr, k, v)
                else -> Settings.Global.putString(cr, k, v)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun setRemovePercentage(enabled: Boolean) {
        if (enabled) saveOriginals()
        val changes = mutableListOf<String>()
        val targets = if (enabled) {
            percentCandidates.map { it to it.hideValue } + backgroundCandidates.map { it to it.hideValue }
        } else {
            percentCandidates.map { it to it.showValue } + backgroundCandidates.map { it to it.showValue }
        }
        targets.forEach { (candidate, value) ->
            val before = read(candidate.ns, candidate.key)
            val ok = write(candidate.ns, candidate.key, value)
            val after = read(candidate.ns, candidate.key)
            if (ok) changes += "${candidate.ns}/${candidate.key}: ${before ?: "<none>"} -> ${after ?: "<none>"}"
        }
        prefs.edit().putBoolean("remove_percentage_enabled", enabled).apply()
        log(
            (if (enabled) "Applied hide-percentage attempt.\n" else "Applied show-percentage defaults.\n") +
                changes.joinToString("\n").ifEmpty { "No settings were writable. Grant WRITE_SECURE_SETTINGS and try again." } +
                "\n\nCheck the status bar now. Use Restore saved battery settings to revert the pre-toggle values."
        )
    }

    private fun saveOriginals() {
        val e = prefs.edit()
        candidates.forEach { c ->
            val prefKey = "original/${c.ns}/${c.key}"
            if (!prefs.contains(prefKey) && !prefs.contains("$prefKey.exists")) {
                val value = read(c.ns, c.key)
                e.putBoolean("$prefKey.exists", value != null)
                if (value != null) e.putString(prefKey, value)
            }
        }
        e.apply()
    }

    private fun restoreSavedSettings() {
        val changes = mutableListOf<String>()
        candidates.forEach { c ->
            val prefKey = "original/${c.ns}/${c.key}"
            if (!prefs.contains("$prefKey.exists")) return@forEach
            val value = if (prefs.getBoolean("$prefKey.exists", false)) prefs.getString(prefKey, null) else null
            val before = read(c.ns, c.key)
            val ok = write(c.ns, c.key, value)
            val after = read(c.ns, c.key)
            if (ok) changes += "${c.ns}/${c.key}: ${before ?: "<none>"} -> ${after ?: "<none>"}"
        }
        prefs.edit().putBoolean("remove_percentage_enabled", false).apply()
        log(changes.joinToString("\n").ifEmpty { "No saved settings to restore, or no settings were writable." })
    }
}
