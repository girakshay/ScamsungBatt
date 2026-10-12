package dev.scamsung.probe

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class PillService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var pill: BatteryPillView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var level = 100
    private var charging = false
    private var shadeOpen = false
    private var dozing = false

    /** Screen bounds of Samsung's own battery view, read from the status bar. Null until first measured. */
    private var measured: Rect? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(i)
    }
    private val displays by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) refreshDozing() }
    }
    private val measureRunnable = Runnable { measure() }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(listener)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        displays.registerDisplayListener(displayListener, null)
        refreshDozing()
        apply()
        scheduleMeasure()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        runCatching { Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(listener) }
        runCatching { unregisterReceiver(battery) }
        runCatching { displays.unregisterDisplayListener(displayListener) }
        remove()
        StockBattery.setHidden(this, false)
    }

    private fun remove() {
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- visibility: notification shade and always-on display ----

    private fun refreshDozing() {
        val state = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_ON
        val now = state != Display.STATE_ON || !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (now == dozing) return
        dozing = now
        updateVisibility()
        scheduleMeasure()
        handler.postDelayed(measureRunnable, 800)
    }

    private fun setShade(open: Boolean) {
        if (open == shadeOpen) return
        shadeOpen = open
        updateVisibility()
        if (!open) scheduleMeasure()
    }

    private fun updateVisibility() {
        val sp = Prefs.sp(this)
        val hide = (shadeOpen && sp.getBoolean(Prefs.HIDE_SHADE, true)) || (dozing && !sp.getBoolean(Prefs.SHOW_AOD, true))
        pill?.let { it.aod = dozing; it.invalidate(); it.visibility = if (hide) View.INVISIBLE else View.VISIBLE }
    }

    // Opening the shade: SystemUI announces "Notification shade."; closing: another package's window takes over.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val t = event.text.joinToString(" ").lowercase()
                if (pkg == SYSTEMUI && ("shade" in t || "quick settings" in t || "quick panel" in t)) setShade(true)
                else if (pkg != null && pkg != SYSTEMUI) setShade(false)
                scheduleMeasure()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (shadeOpen && windows.size > 1) setShade(false)
                scheduleMeasure()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == SYSTEMUI) scheduleMeasure()
        }
    }

    // ---- size and position: follow Samsung's own battery view exactly ----

    private fun scheduleMeasure() {
        handler.removeCallbacks(measureRunnable)
        handler.postDelayed(measureRunnable, 150)
    }

    private fun measure() {
        val sp = Prefs.sp(this)
        if (!sp.getBoolean(Prefs.ENABLED, false) || !sp.getBoolean(Prefs.AUTO_SIZE, true) || shadeOpen) return
        var found: Rect? = null
        runCatching {
            for (w in windows) {
                val root = w.root ?: continue
                for (n in root.findAccessibilityNodeInfosByViewId(BATTERY_ID)) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.top < STATUS_BAR_MAX_TOP) { found = r; break }
                }
                if (found != null) break
            }
        }
        val r = found ?: return
        if (r != measured) { measured = r; apply() }
    }

    private fun readBattery(i: Intent) {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (lvl >= 0) level = lvl * 100 / scale
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun apply() {
        val sp = Prefs.sp(this)
        if (!sp.getBoolean(Prefs.ENABLED, false)) {
            remove()
            StockBattery.setHidden(this, false)
            return
        }
        StockBattery.setHidden(this, sp.getBoolean(Prefs.HIDE_STOCK, false))
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }

        val h = dp(sp.getInt(Prefs.HEIGHT_DP, 14))
        val screenW = wm.currentWindowMetrics.bounds.width()
        val m = if (sp.getBoolean(Prefs.AUTO_SIZE, true)) measured else null
        val w: Int; val x: Int; val y: Int
        if (m != null) {
            val right = m.right - RIGHT_TRIM_PX
            val left = m.left - LEFT_GROW_PX
            w = right - left + dp(sp.getInt(Prefs.WIDTH_DP, 0))
            x = screenW - right + dp(sp.getInt(Prefs.RIGHT_DP, 0))
            y = m.centerY() - h / 2 + dp(sp.getInt(Prefs.ADJ_Y_DP, 0))
        } else {
            // Fallback sizes tuned on a Galaxy S25: Samsung's pill grows with a bolt and with a third digit.
            val base = FALLBACK_WIDTH_DP + sp.getInt(Prefs.WIDTH_DP, 0) +
                (if (charging) 6 else 0) + (if (level >= 100) 6 else 0)
            val statusH = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else dp(32) }
            w = dp(base)
            x = dp(FALLBACK_RIGHT_DP + sp.getInt(Prefs.RIGHT_DP, 0))
            y = (statusH - h) / 2 + dp(1 + sp.getInt(Prefs.ADJ_Y_DP, 0))
        }

        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            this.x = x; this.y = y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val v = pill ?: BatteryPillView(this).also {
            pill = it
            wm.addView(it, lp)
        }
        v.dark = sp.getBoolean(Prefs.DARK_ICONS, false)
        v.level = level
        v.charging = charging
        updateVisibility()
        if (v.isAttachedToWindow) wm.updateViewLayout(v, lp)
        v.invalidate()
    }

    private fun update(i: Intent) {
        readBattery(i)
        pill?.apply { level = this@PillService.level; charging = this@PillService.charging; invalidate() }
        if (measured == null) apply()
        // Samsung re-lays out its pill a moment after the plug state or digit count changes.
        scheduleMeasure()
        handler.postDelayed(measureRunnable, 700)
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val BATTERY_ID = "com.android.systemui:id/battery"
        const val STATUS_BAR_MAX_TOP = 300
        // Samsung's view bounds include padding: cover the pill edge to edge, but leave a gap to the signal icons.
        const val RIGHT_TRIM_PX = 3
        const val LEFT_GROW_PX = 8
        const val FALLBACK_WIDTH_DP = 25
        const val FALLBACK_RIGHT_DP = 12
    }
}
