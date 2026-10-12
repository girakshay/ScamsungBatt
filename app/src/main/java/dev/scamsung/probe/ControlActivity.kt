package dev.scamsung.probe

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*

class ControlActivity : Activity() {
    private lateinit var info: TextView
    private val sp by lazy { Prefs.sp(this) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 56, 40, 40) }

        fun sw(label: String, key: String, def: Boolean, after: () -> Unit = {}) = root.addView(Switch(this).apply {
            text = label; textSize = 16f; setPadding(0, 24, 0, 24)
            isChecked = sp.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> sp.edit().putBoolean(key, v).apply(); after() }
        })

        fun slider(label: String, key: String, def: Int, min: Int, max: Int) {
            val tv = TextView(this)
            val cur = sp.getInt(key, def)
            tv.text = "$label: $cur dp"
            root.addView(tv)
            root.addView(SeekBar(this).apply {
                this.max = max - min; progress = cur - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                        tv.text = "$label: ${p + min} dp"
                        if (u) sp.edit().putInt(key, p + min).apply()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }

        root.addView(TextView(this).apply { text = "Battery Pill Control"; textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(attrColor(android.R.attr.textColorPrimary)); setPadding(0, 0, 0, 16) })
        sw("Remove percentage from battery pill", Prefs.ENABLED, false) { refresh() }
        sw("Ask Samsung to hide its number too (icons stay in place)", Prefs.HIDE_STOCK, false)
        sw("Dark fill (use on light backgrounds)", Prefs.DARK_ICONS, false)
        sw("Match Samsung's battery size and position automatically", Prefs.AUTO_SIZE, true)
        sw("Hide pill in notification shade", Prefs.HIDE_SHADE, true)
        sw("Show pill on always-on display", Prefs.SHOW_AOD, true)
        slider("Fine-tune: move left", Prefs.RIGHT_DP, 0, -10, 20)
        slider("Fine-tune: move down", Prefs.ADJ_Y_DP, 0, -12, 12)
        slider("Fine-tune: extra width", Prefs.WIDTH_DP, 0, -10, 15)
        slider("Pill height", Prefs.HEIGHT_DP, 14, 8, 30)

        root.addView(Button(this).apply { text = "Reset positions to defaults"; setOnClickListener {
            sp.edit().remove(Prefs.RIGHT_DP).remove(Prefs.ADJ_Y_DP).remove(Prefs.WIDTH_DP).remove(Prefs.HEIGHT_DP).apply(); recreate()
        } })
        root.addView(Button(this).apply { text = "Open Accessibility settings"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        root.addView(Button(this).apply { text = "Open App info (allow restricted settings)"; setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        } })
        root.addView(Button(this).apply { text = "Restore Samsung battery now"; setOnClickListener {
            sp.edit().putBoolean(Prefs.ENABLED, false).apply()
            StockBattery.setHidden(this@ControlActivity, false); recreate()
        } })
        root.addView(Button(this).apply { text = "Probe tools"; setOnClickListener { startActivity(Intent(this@ControlActivity, MainActivity::class.java)) } })
        info = TextView(this).apply { textSize = 12f; setPadding(0, 24, 0, 0); setTextColor(attrColor(android.R.attr.textColorSecondary)) }
        root.addView(info)
        for (i in 0 until root.childCount) (root.getChildAt(i) as? Button)?.layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 }
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }.also { sv ->
            sv.setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                i
            }
        })
    }

    private fun attrColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        return a.getColor(0, 0).also { a.recycle() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val perm = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        val svc = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains("$packageName/")
        info.text = "WRITE_SECURE_SETTINGS: ${if (perm) "granted" else "NOT granted"}\n" +
            (if (perm) "" else "  adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n") +
            "Accessibility service: ${if (svc) "on" else "OFF - enable 'Battery Pill Control' in Accessibility > Installed apps"}\n" +
            "Samsung number hidden by system: ${runCatching { StockBattery.hidden(this) }.getOrDefault(false)}"
    }
}
