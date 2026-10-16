package dev.scamsung.probe

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Samsung-style capsule: lighter track, darker level fill clipped to the capsule, lightning bolt when charging.
 * No digits. Opaque so it fully covers Samsung's own pill and number underneath.
 */
class BatteryPillView(c: Context) : View(c) {
    var level = 100
    var charging = false
    /** Battery saver / Samsung "Power saving" is on: yellow fill and a "+" glyph (bolt wins when only one fits). */
    var powerSave = false
    /** true = dark fill on light track (for light backgrounds); false = light fill on dark track. Cross-fades. */
    var dark = true
        set(v) {
            if (field == v) return
            field = v
            if (!styleSet) { mix = if (v) 1f else 0f; invalidate(); return }
            fade?.cancel()
            fade = ValueAnimator.ofFloat(mix, if (v) 1f else 0f).apply {
                duration = 70
                addUpdateListener { mix = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var styleSet = false
    private var mix = 1f             // 1 = dark-fill style, 0 = light-fill style
    private var fade: ValueAnimator? = null
    private val argb = ArgbEvaluator()
    private fun lerp(light: Int, darkC: Int) = argb.evaluate(mix, light, darkC) as Int

    /** Call once the first style is known so later changes animate instead of snapping. */
    fun markStyleReady() { styleSet = true }
    /** Always-on display: dim colours (low brightness, burn-in friendly). */
    var aod = false
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val boltPath = Path()
    private val plusPath = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val low = !charging && level <= 15
        if (aod) {
            track.color = 0xFF3A3A3C.toInt()
            fill.color = when { low -> 0xFF8E2A24.toInt(); powerSave -> 0xFF9A7A1E.toInt(); else -> 0xFF9A9AA0.toInt() }
            bolt.color = 0xFF1C1C1E.toInt()
        } else if (powerSave && !low) {
            track.color = lerp(0xFF55595E.toInt(), 0xFF9BA3AB.toInt())
            fill.color = 0xFFFFC93C.toInt()
            bolt.color = 0xFF2C2C2E.toInt()
        } else {
            track.color = lerp(0xFF55595E.toInt(), 0xFF9BA3AB.toInt())
            fill.color = if (low) 0xFFFF3B30.toInt() else lerp(0xFFF2F2F7.toInt(), 0xFF3A3F44.toInt())
            bolt.color = if (low) 0xFFFFFFFF.toInt() else lerp(0xFF2C2C2E.toInt(), 0xFFFFFFFF.toInt())
        }

        rect.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        canvas.drawPath(clip, track)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w * level.coerceIn(0, 100) / 100f, h, fill)
        canvas.restore()

        // Glyphs: bolt while charging, "+" for power saving; both side by side when both apply.
        val both = charging && powerSave
        val size = if (both) h * 0.5f else h * 0.72f
        if (charging) drawBolt(canvas, if (both) w * 0.64f else w / 2f, h / 2f, size)
        if (powerSave) drawPlus(canvas, if (both) w * 0.34f else w / 2f, h / 2f, if (both) size * 0.8f else h * 0.56f)
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, bh: Float) {
        val bw = bh * 0.62f
        val l = cx - bw / 2f; val t = cy - bh / 2f
        boltPath.reset()
        boltPath.moveTo(l + bw * 0.62f, t)
        boltPath.lineTo(l, t + bh * 0.56f)
        boltPath.lineTo(l + bw * 0.46f, t + bh * 0.56f)
        boltPath.lineTo(l + bw * 0.34f, t + bh)
        boltPath.lineTo(l + bw, t + bh * 0.40f)
        boltPath.lineTo(l + bw * 0.54f, t + bh * 0.40f)
        boltPath.close()
        canvas.drawPath(boltPath, bolt)
    }

    private fun drawPlus(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size / 2f; val t = size * 0.18f
        plusPath.reset()
        plusPath.addRect(cx - half, cy - t, cx + half, cy + t, Path.Direction.CW)
        plusPath.addRect(cx - t, cy - half, cx + t, cy + half, Path.Direction.CW)
        canvas.drawPath(plusPath, bolt)
    }
}
