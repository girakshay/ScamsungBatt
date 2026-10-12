package dev.scamsung.probe

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

object Prefs {
    const val NAME = "pill"
    const val ENABLED = "enabled"
    const val HIDE_STOCK = "hide_stock_v2"
    const val DARK_ICONS = "dark_icons"
    const val HIDE_SHADE = "hide_shade"
    const val SHOW_AOD = "show_aod"
    const val COVER_ANIM = "cover_anim"
    /** 0 = auto (match the status bar background), 1 = dark fill, 2 = light fill. */
    const val PILL_STYLE = "pill_style"
    const val AUTO_SIZE = "auto_size"
    const val RIGHT_DP = "right_dp_v6"
    const val ADJ_Y_DP = "adj_y_dp_v6"
    const val WIDTH_DP = "width_dp_v6"
    const val HEIGHT_DP = "height_dp_v2"

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

/**
 * Adds/removes "battery_percent" in Settings.Secure icon_blacklist (needs WRITE_SECURE_SETTINGS).
 * This asks SystemUI to hide only the number. The pill slot stays, so other status icons never shift
 * (hiding the whole battery with "battery_icon" collapses the slot and moves the Wi-Fi/signal icons).
 */
object StockBattery {
    private const val KEY = "icon_blacklist"
    private const val TOKEN = "battery_percent"
    // Tokens we may have written before; always cleaned so nothing stays hidden by accident.
    private val OWN_TOKENS = setOf("battery", "battery_icon", TOKEN)

    fun hidden(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, KEY).orEmpty().split(",").contains(TOKEN)

    fun setHidden(c: Context, hide: Boolean): Boolean = try {
        val cur = Settings.Secure.getString(c.contentResolver, KEY).orEmpty()
        val parts = cur.split(",").filter { it.isNotBlank() && it !in OWN_TOKENS }
        val next = (if (hide) parts + TOKEN else parts).joinToString(",")
        if (next == cur) true else Settings.Secure.putString(c.contentResolver, KEY, next)
    } catch (_: Exception) {
        false
    }
}
