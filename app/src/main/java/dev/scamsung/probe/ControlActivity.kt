package dev.scamsung.probe

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Settings screen: Jetpack Compose with Material 3 Expressive (theme, motion, shape-morphing buttons) and Material You colours. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class ControlActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ControlTheme { ControlScreen(resumes) } }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ControlTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ControlScreen(resumes: Int) {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    // Bumped by the buttons that reset or restore, to rebuild every control from the saved values.
    var epoch by remember { mutableIntStateOf(0) }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Battery Pill Control",
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatusCard(resumes)

            androidx.compose.runtime.key(epoch) {
                Section("Pill") {
                    PrefSwitch("Remove percentage from battery pill", "Draw a pill without the number", Prefs.ENABLED, false, sp)
                    PrefSwitch("Ask Samsung to hide its number too", "Icons stay in place", Prefs.HIDE_STOCK, false, sp)
                    PrefSwitch("Dark fill", "Use on light backgrounds", Prefs.DARK_ICONS, false, sp)
                    PrefSwitch("Match Samsung's size and position", "Follows the system battery icon", Prefs.AUTO_SIZE, true, sp)
                }
                Section("Where it shows") {
                    PrefSwitch("Hide in notification shade", "Samsung's own battery shows there", Prefs.HIDE_SHADE, true, sp)
                    PrefSwitch("Show on always-on display", "Dim style on the AOD", Prefs.SHOW_AOD, true, sp)
                }
                Section("Fine-tune") {
                    PrefSlider("Move left", Prefs.RIGHT_DP, 0, -10, 20, sp)
                    PrefSlider("Move down", Prefs.ADJ_Y_DP, 0, -12, 12, sp)
                    PrefSlider("Extra width", Prefs.WIDTH_DP, 0, -10, 15, sp)
                    PrefSlider("Pill height", Prefs.HEIGHT_DP, 14, 8, 30, sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveButton("Reset positions") {
                    sp.edit().remove(Prefs.RIGHT_DP).remove(Prefs.ADJ_Y_DP).remove(Prefs.WIDTH_DP).remove(Prefs.HEIGHT_DP).apply()
                    epoch++
                }
                ExpressiveButton("Open Accessibility settings", tonal = true) {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                ExpressiveButton("Open App info (allow restricted settings)", tonal = true) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")),
                    )
                }
                ExpressiveButton("Restore Samsung battery now", tonal = true) {
                    sp.edit().putBoolean(Prefs.ENABLED, false).apply()
                    StockBattery.setHidden(ctx, false)
                    epoch++
                }
                ExpressiveButton("Probe tools", tonal = true) {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(resumes: Int) {
    val ctx = LocalContext.current
    val perm = remember(resumes) {
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }
    val svc = remember(resumes) {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .contains("${ctx.packageName}/")
    }
    val hidden = remember(resumes) { runCatching { StockBattery.hidden(ctx) }.getOrDefault(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusLine("Write permission", perm, if (perm) "granted" else "missing")
            if (!perm) {
                Text(
                    "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            StatusLine("Accessibility service", svc, if (svc) "on" else "off")
            StatusLine("Samsung number hidden by system", hidden, if (hidden) "yes" else "no")
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, value: String) {
    Text(
        "${if (ok) "●" else "○"}  $label: $value",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun PrefSwitch(
    title: String,
    subtitle: String,
    key: String,
    default: Boolean,
    sp: SharedPreferences,
) {
    var on by remember { mutableStateOf(sp.getBoolean(key, default)) }
    val toggle = { v: Boolean -> on = v; sp.edit().putBoolean(key, v).apply() }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = on, onCheckedChange = toggle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { toggle(!on) },
    )
}

@Composable
private fun PrefSlider(label: String, key: String, default: Int, min: Int, max: Int, sp: SharedPreferences) {
    var v by remember { mutableFloatStateOf(sp.getInt(key, default).toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("$label: ${v.roundToInt()} dp", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = v,
            onValueChange = { v = it; sp.edit().putInt(key, it.roundToInt()).apply() },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveButton(text: String, tonal: Boolean = false, onClick: () -> Unit) {
    val mod = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight)
    if (tonal) {
        FilledTonalButton(onClick = onClick, modifier = mod, shapes = ButtonDefaults.shapes()) { Text(text) }
    } else {
        Button(onClick = onClick, modifier = mod, shapes = ButtonDefaults.shapes()) { Text(text) }
    }
}
