package dev.scamsung.probe

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.provider.Settings
import android.os.SystemClock
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PillService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var pill: BatteryPillView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var level = 100
    private var charging = false
    private var shadeOpen = false
    private var dozing = false
    /** Auto colour: true when the status bar background is light (so icons and the pill are dark). Null until sampled. */
    private var lightBackground: Boolean? = null
    private var lastSampleAt = 0L
    private var currentPkg: String? = null
    private val styleByPkg = HashMap<String, Boolean>()   // package -> status bar background was light
    private var burst: ChargeBurstView? = null
    private var burstShimmer: ValueAnimator? = null
    private var lastX = 0          // pill window right inset, px
    private var lastCenterY = 0    // pill vertical centre, px

    /** Screen bounds of Samsung's own battery view, read from the status bar. Null until first measured. */
    private var measured: Rect? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(i)
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshDozing()
            if (i.action != Intent.ACTION_SCREEN_OFF) { scheduleSample(); pollAppearance(POLL_AFTER_EVENT_MS) }
        }
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
        // Read the current plug state first so the sticky broadcast below is not mistaken for a new plug-in.
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        displays.registerDisplayListener(displayListener, null)
        loadMeasured()
        loadStyleMemory()
        refreshDozing()
        apply()
        scheduleMeasure()
        scheduleSample()
        handler.postDelayed(idlePoll, IDLE_POLL_GAP_MS)
        pollAppearance(POLL_AFTER_EVENT_MS)
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
        dumpExec.shutdownNow()
        runCatching { Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(listener) }
        runCatching { unregisterReceiver(battery) }
        runCatching { unregisterReceiver(screen) }
        endBurst(true)
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
        if (!now) pollAppearance(POLL_AFTER_EVENT_MS)
        scheduleMeasure()
        handler.postDelayed(measureRunnable, 800)
    }

    private fun setShade(open: Boolean) {
        if (open == shadeOpen) return
        shadeOpen = open
        updateVisibility()
        if (open) handler.postDelayed(shadeCheck, SHADE_CHECK_MS) else scheduleMeasure()
    }

    // SystemUI also announces "Notification shade." for the PIN screen on the lock screen. In the real shade the status
    // row (and Samsung's battery view) slides down, from about y=23 to y=128; on the PIN screen it stays where it is.
    private val shadeConfirm = object : Runnable {
        var until = 0L
        override fun run() {
            val top = batteryTop()
            when {
                top != null && top > SHADE_NODE_TOP -> setShade(true)
                SystemClock.uptimeMillis() < until -> handler.postDelayed(this, 50)
                top == null -> setShade(true)   // cannot tell: keep the old behaviour and assume the shade
            }
        }
    }

    private fun confirmShade() {
        if (shadeOpen) return
        shadeConfirm.until = SystemClock.uptimeMillis() + SHADE_CONFIRM_MS
        handler.removeCallbacks(shadeConfirm)
        handler.post(shadeConfirm)
    }

    private fun batteryTop(): Int? = runCatching {
        for (w in windows) findBattery(w.root, 0)?.let { return@runCatching it.top }
        null
    }.getOrNull()

    // The shade can be flagged open by an event (the unlock also fires one) without a matching close event.
    // With the shade really open the window list is a single full-screen window, so clear the flag otherwise.
    private val shadeCheck = object : Runnable {
        override fun run() {
            if (!shadeOpen) return
            if (runCatching { windows.size }.getOrDefault(1) > 1) setShade(false)
            else handler.postDelayed(this, SHADE_CHECK_MS)
        }
    }

    private fun updateVisibility() {
        val sp = Prefs.sp(this)
        val hide = (shadeOpen && sp.getBoolean(Prefs.HIDE_SHADE, true)) || (dozing && !sp.getBoolean(Prefs.SHOW_AOD, true)) || barHidden
        pill?.let { it.aod = dozing; it.invalidate(); it.visibility = if (hide) View.INVISIBLE else View.VISIBLE }
    }

    // Opening the shade: SystemUI announces "Notification shade."; closing: another package's window takes over.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        refreshDozing()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val top = focusedPackage() ?: pkg
            if (usable(top)) applyRemembered(top!!)
            scheduleSample()
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val t = event.text.joinToString(" ").lowercase()
                if (pkg == SYSTEMUI && ("shade" in t || "quick settings" in t || "quick panel" in t)) confirmShade()
                else if (pkg != null && pkg != SYSTEMUI) setShade(false)
                scheduleMeasure()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (shadeOpen && windows.size > 1) setShade(false)
                // This fires before the window-state event at the start of an app switch: restyle from the focused app.
                val t = android.os.SystemClock.uptimeMillis()
                // A cold-started app announces its window late, but the window change fires at the start of the
                // transition, so begin sampling then (rate-limited, the screenshot API allows ~3 per second).
                if (t - lastSwitchSample > SWITCH_SAMPLE_GAP_MS) { lastSwitchSample = t; scheduleSample() }
                if (t - lastFocusCheck > 40) {
                    lastFocusCheck = t
                    focusedPackage()?.let { top ->
                        if (usable(top) && top != currentPkg) { applyRemembered(top); scheduleSample() }
                    }
                }
                scheduleMeasure()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == SYSTEMUI) {
                scheduleMeasure()
                val t = SystemClock.uptimeMillis()
                if (t - lastContentPoll > 250) { lastContentPoll = t; pollAppearance(250) }
            }
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
                found = findBattery(w.root, 0)
                if (found != null) break
            }
        }
        val r = found ?: return
        if (r != measured) {
            measured = r
            getSharedPreferences(MEASURED_PREFS, MODE_PRIVATE).edit()
                .putString("rect", "${r.left},${r.top},${r.right},${r.bottom}").apply()
            apply()
        }
    }

    // findAccessibilityNodeInfosByViewId returned nothing on One UI 9, so walk the tree and match the view id.
    // The lock screen's battery has no id, only a "Battery ..." description, so accept that near the top of the screen.
    private fun findBattery(n: AccessibilityNodeInfo?, depth: Int): Rect? {
        n ?: return null
        val id = n.viewIdResourceName
        val byId = id == BATTERY_ID
        val byDesc = id == null && n.contentDescription?.startsWith("Battery", ignoreCase = true) == true
        if (byId || byDesc) {
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() > 0 && r.top < STATUS_BAR_MAX_TOP) return r
        }
        if (depth > 14) return null
        for (i in 0 until n.childCount) findBattery(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun loadMeasured() {
        val parts = getSharedPreferences(MEASURED_PREFS, MODE_PRIVATE).getString("rect", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (parts != null && parts.size == 4) measured = Rect(parts[0], parts[1], parts[2], parts[3])
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

        lastX = x; lastCenterY = y + h / 2
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
        v.dark = darkFill(sp)
        if (lightBackground != null || sp.getInt(Prefs.PILL_STYLE, 0) != 0) v.markStyleReady()
        v.level = level
        v.charging = charging
        updateVisibility()
        if (v.isAttachedToWindow) wm.updateViewLayout(v, lp)
        v.invalidate()
    }

    private fun update(i: Intent) {
        val wasCharging = charging
        readBattery(i)
        if (!wasCharging && charging) startBurst()
        if (wasCharging && !charging) endBurst(true)
        pill?.apply { level = this@PillService.level; charging = this@PillService.charging; invalidate() }
        if (measured == null) apply()
        // Samsung re-lays out its pill a moment after the plug state or digit count changes.
        scheduleMeasure()
        handler.postDelayed(measureRunnable, 700)
    }

    // ---- pill colour: match the status bar icons (dark icons on a light bar, white icons on a dark bar) ----

    private fun darkFill(sp: SharedPreferences): Boolean = when (sp.getInt(Prefs.PILL_STYLE, 0)) {
        1 -> true
        2 -> false
        else -> lightBackground ?: !isNight()
    }

    private fun isNight() =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // ---- exact status bar appearance from the window manager (needs the DUMP permission) ----
    // This is the same flag SystemUI uses to tint the status icons, so it flips with them, even for an app seen
    // for the first time. Reading it takes ~25 ms; the screenshot sampling below is only the fallback.
    private val dumpExec = Executors.newSingleThreadExecutor()
    private var polling = false
    private var pollUntil = 0L
    private var dumpFailed = false
    private val dumpMode get() =
        !dumpFailed && checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    /** true = light status bar (dark icons), false = dark status bar (white icons), null = could not read. */
    private fun readLightBars(): Boolean? {
        val r = runCatching {
            // Same output as `dumpsys window displays`, but straight from the window service's binder: apps cannot run
            // the dumpsys tool for it ("Can't find service"), yet DUMP lets them call dump() on the binder.
            val binder = windowBinder ?: (Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window") as android.os.IBinder?)
                ?.also { windowBinder = it } ?: error("no window binder")
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            val rd = pipe[0]; val wr = pipe[1]
            var text = ""
            val reader = Thread { text = java.io.FileInputStream(rd.fileDescriptor).bufferedReader().readText() }
            reader.start()
            try { binder.dump(wr.fileDescriptor, arrayOf("displays")) } finally { wr.close() }
            reader.join(500)
            rd.close()
            if (text.length < 2000) null
            else {
                // Same dump: the status bar's inset source says visible=false while an app is in full screen mode.
                text.lineSequence().firstOrNull { it.contains("InsetsSource") && it.contains("type=statusBars") }
                    ?.let { statusBarShown = it.contains("visible=true") }
                text.lineSequence().firstOrNull { it.contains("mLastAppearance=") }?.contains("LIGHT_STATUS_BARS") == true
            }
        }
        return r.getOrNull()
    }

    private var windowBinder: android.os.IBinder? = null
    @Volatile private var statusBarShown = true

    private fun pollAppearance(forMs: Long) {
        if (!dumpMode) return
        pollUntil = maxOf(pollUntil, SystemClock.uptimeMillis() + forMs)
        if (polling) return
        polling = true
        dumpExec.execute {
            try {
                while (SystemClock.uptimeMillis() < pollUntil) {
                    val light = readLightBars()
                    if (light == null) { dumpFailed = true; break }
                    val shown = statusBarShown
                    handler.post { applyAppearance(light); applyBarShown(shown) }
                    Thread.sleep(8)
                }
            } finally { polling = false }
        }
    }

    // The lock screen is drawn by SystemUI, which picks its own icon colour from the wallpaper. The window manager's
    // appearance flag only describes the last app, so on the keyguard fall back to sampling the bar.
    private fun onKeyguard() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /**
     * The lock screen's own icon colour: Samsung's wallpaper service writes `white_lockscreen_statusbar` (SystemUI reads
     * it as isWhiteKeyguardStatusBar) from the lock wallpaper's brightness. 1 = light wallpaper, so dark icons.
     * Unlike sampling pixels it does not depend on the wallpaper having finished fading in after the AOD.
     */
    private fun lockscreenLight(): Boolean? =
        when (Settings.System.getInt(contentResolver, "white_lockscreen_statusbar", -1)) { 1 -> true; 0 -> false; else -> null }

    private fun applyLight(light: Boolean) {
        if (light != lightBackground) { lightBackground = light; restyle() }
    }

    // In an app's full screen mode the status bar is gone but our overlay would stay: hide the pill with it.
    private var barHidden = false
    private fun applyBarShown(shown: Boolean) {
        val hidden = !shown && !onKeyguard() && !dozing
        if (hidden != barHidden) { barHidden = hidden; updateVisibility() }
    }

    private fun applyAppearance(light: Boolean) {
        if (onKeyguard()) return
        if (Prefs.sp(this).getInt(Prefs.PILL_STYLE, 0) != 0 || dozing) return
        if (light != lightBackground) { lightBackground = light; restyle() }
    }

    // Also catches colour changes inside one app (a page or screen that changes the bar) that fire no window event.
    private val idlePoll = object : Runnable {
        override fun run() {
            if (!dozing && (dumpMode || onKeyguard())) {
                if (onKeyguard()) {
                    val l = lockscreenLight()
                    if (l != null) applyLight(l)
                    else {
                        val t = SystemClock.uptimeMillis()
                        if (t - lastKeyguardSample > 2500) { lastKeyguardSample = t; scheduleSample() }
                    }
                } else pollAppearance(IDLE_POLL_MS)
            }
            handler.postDelayed(this, IDLE_POLL_GAP_MS)
        }
    }

    private var lastKeyguardSample = 0L
    private var sampleGen = 0
    private var burstStart = 0L
    private var burstPkg: String? = null
    private var lastFocusCheck = 0L
    private var lastContentPoll = 0L
    private var lastSwitchSample = 0L

    // Sample right away, then again as the app transition settles. Only the late samples are trusted enough to
    // be remembered: an early one can still be looking at the screen we just left.
    private fun scheduleSample() {
        if (Prefs.sp(this).getInt(Prefs.PILL_STYLE, 0) != 0) return
        if (onKeyguard()) { lockscreenLight()?.let { applyLight(it); return } }
        if (dumpMode && !onKeyguard()) { pollAppearance(POLL_AFTER_EVENT_MS); return }
        val now = android.os.SystemClock.uptimeMillis()
        // Let a running burst finish: a cold-starting app fires a stream of window events, and restarting the timer
        // on each one would starve the samples. A different foreground app starts a fresh burst.
        if (now - burstStart < SAMPLE_BURST_MS && burstPkg == currentPkg) return
        handler.removeCallbacksAndMessages(SAMPLE_TAG)
        burstStart = now; burstPkg = currentPkg
        val gen = ++sampleGen
        for (d in SAMPLE_DELAYS_MS) handler.postDelayed({ sampleBackground(gen, d >= SAMPLE_TRUSTED_AFTER_MS) }, SAMPLE_TAG, d)
    }

    // An app seen before keeps its status bar colour, so switch the pill instantly and let sampling confirm it.
    private fun applyRemembered(pkg: String) {
        currentPkg = pkg
        if (dumpMode && !onKeyguard()) return
        val remembered = styleByPkg[pkg] ?: guessFromHistory() ?: return
        if (remembered == lightBackground) return
        lightBackground = remembered
        restyle()
    }

    private fun usable(pkg: String?) =
        pkg != null && pkg != SYSTEMUI && pkg != packageName && !pkg.contains("inputmethod") && !pkg.contains("biometrics")

    /** The app that really owns the focused window now. Event package names are unreliable: an app also fires events on its way out. */
    private fun focusedPackage(): String? =
        runCatching { windows.firstOrNull { it.isFocused }?.root?.packageName?.toString() }.getOrNull()

    // For an app not seen before: if the apps you use mostly share one status bar colour, assume the same
    // (the sample then confirms it). With no clear lean, don't guess: a wrong flash is worse than a short wait.
    private fun guessFromHistory(): Boolean? {
        val seen = styleByPkg.filterKeys { !it.contains("launcher") }.values
        if (seen.size < 3) return null
        val lightShare = seen.count { it }.toDouble() / seen.size
        return when {
            lightShare >= 0.7 -> true
            lightShare <= 0.3 -> false
            else -> null
        }
    }

    private fun rememberStyle(pkg: String, light: Boolean) {
        if (styleByPkg[pkg] == light) return
        styleByPkg[pkg] = light
        getSharedPreferences(STYLE_MEMORY_PREFS, MODE_PRIVATE).edit().putBoolean(pkg, light).apply()
    }

    private fun loadStyleMemory() {
        getSharedPreferences(STYLE_MEMORY_PREFS, MODE_PRIVATE).all.forEach { (k, v) -> if (v is Boolean) styleByPkg[k] = v }
    }

    private fun restyle() {
        pill?.let { it.dark = darkFill(Prefs.sp(this)); it.markStyleReady() }
    }

    // Looks at a few pixels in the empty middle of the status bar (left of the camera cut-out, right of the clock).
    // The screenshot is cropped, read, and dropped immediately.
    private fun sampleBackground(gen: Int, trusted: Boolean) {
        val owner = currentPkg
        val now = android.os.SystemClock.uptimeMillis()
        if (gen != sampleGen || now - lastSampleAt < SAMPLE_MIN_INTERVAL_MS || dozing || shadeOpen) return
        lastSampleAt = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val light = runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val crop = hw?.let { Bitmap.createBitmap(it, 0, 0, it.width, 40).copy(Bitmap.Config.ARGB_8888, false) }
                    hw?.recycle()
                    crop?.let { bmp ->
                        var sum = 0.0
                        val xs = listOf(0.28f, 0.34f, 0.40f)
                        xs.forEach { f ->
                            val c = bmp.getPixel((bmp.width * f).toInt(), 14)
                            sum += (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
                        }
                        bmp.recycle()
                        sum / xs.size > 0.55
                    }
                }.getOrNull()
                runCatching { result.hardwareBuffer.close() }
                if (light != null && gen == sampleGen) {
                    if (trusted && !onKeyguard() && owner != null && owner == currentPkg && focusedPackage() == owner) rememberStyle(owner, light)
                    if (light != lightBackground) { lightBackground = light; restyle() }
                }
            }
            override fun onFailure(errorCode: Int) {}
        })
    }

    // ---- cover Samsung's "charger connected" animation (a wide green pill with a bolt, about 3 s) ----

    private fun startBurst() {
        val sp = Prefs.sp(this)
        if (!sp.getBoolean(Prefs.ENABLED, false) || !sp.getBoolean(Prefs.COVER_ANIM, true) || dozing || shadeOpen) return
        endBurst(true)
        handler.postDelayed({ showBurst() }, BURST_TAG, BURST_DELAY_MS)
    }

    private fun showBurst() {
        if (dozing || shadeOpen || !charging || lastX == 0) return
        val screenW = wm.currentWindowMetrics.bounds.width()
        val lp = WindowManager.LayoutParams(
            BURST_W_PX, BURST_H_PX, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (lastX - 2).coerceAtLeast(0); y = lastCenterY - BURST_H_PX / 2
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val v = ChargeBurstView(this).apply {
            level = this@PillService.level
            pivotX = BURST_W_PX.toFloat(); pivotY = BURST_H_PX / 2f
            alpha = 0f; scaleX = 0.4f; scaleY = 0.55f
        }
        runCatching { wm.addView(v, lp) }.onFailure { return }
        burst = v
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(1.1f)).start()
        burstShimmer = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { v.phase = it.animatedValue as Float }
            start()
        }
        handler.postDelayed({ endBurst(false) }, BURST_TAG, 180 + BURST_HOLD_MS)
    }

    private fun endBurst(immediately: Boolean) {
        handler.removeCallbacksAndMessages(BURST_TAG)
        val v = burst ?: return
        burst = null
        val done = { burstShimmer?.cancel(); burstShimmer = null; runCatching { wm.removeView(v) }; Unit }
        if (immediately) { v.animate().cancel(); done() }
        else v.animate().alpha(0f).scaleX(0.4f).scaleY(0.55f).setDuration(240).withEndAction { done() }.start()
    }

    private companion object {
        const val SHADE_CHECK_MS = 400L
        const val SHADE_CONFIRM_MS = 700L
        const val SHADE_NODE_TOP = 80
        const val POLL_AFTER_EVENT_MS = 900L
        const val IDLE_POLL_MS = 60L
        const val IDLE_POLL_GAP_MS = 300L
        const val SAMPLE_MIN_INTERVAL_MS = 330L
        // Every ~340 ms (the screenshot API limit) for ~2.5 s: a cold-starting app changes the bar tint a second or more in.
        val SAMPLE_DELAYS_MS = longArrayOf(170, 510, 850, 1190, 1530, 1870, 2210, 2550)
        val SAMPLE_TAG = Any()
        const val SAMPLE_TRUSTED_AFTER_MS = 700L
        const val SWITCH_SAMPLE_GAP_MS = 900L
        const val SAMPLE_BURST_MS = 2700L
        const val STYLE_MEMORY_PREFS = "style_memory6"
        val BURST_TAG = Any()
        // Samsung's animation, measured on a Galaxy S25: about 288x83 px, right-aligned with the normal pill, ~4 s.
        const val BURST_W_PX = 296
        const val BURST_H_PX = 90
        const val BURST_DELAY_MS = 0L
        const val BURST_HOLD_MS = 4200L
        const val SYSTEMUI = "com.android.systemui"
        const val MEASURED_PREFS = "measured"
        const val BATTERY_ID = "com.android.systemui:id/battery"
        const val STATUS_BAR_MAX_TOP = 300
        // Samsung's view bounds include padding: cover the pill edge to edge, but leave a gap to the signal icons.
        const val RIGHT_TRIM_PX = -1
        const val LEFT_GROW_PX = 7
        const val FALLBACK_WIDTH_DP = 25
        const val FALLBACK_RIGHT_DP = 12
    }
}
