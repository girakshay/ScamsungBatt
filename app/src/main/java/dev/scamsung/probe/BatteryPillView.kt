package dev.scamsung.probe

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
    /** true = dark fill on light track (for light backgrounds); false = light fill on dark track. */
    var dark = true
    /** Always-on display: dim colours (low brightness, burn-in friendly). */
    var aod = false
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val boltPath = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (aod) {
            track.color = 0xFF3A3A3C.toInt()
            fill.color = if (!charging && level <= 15) 0xFF8E2A24.toInt() else 0xFF9A9AA0.toInt()
            bolt.color = 0xFF1C1C1E.toInt()
        } else {
            track.color = if (dark) 0xFF9BA3AB.toInt() else 0xFF55595E.toInt()
            fill.color = when {
                !charging && level <= 15 -> 0xFFFF3B30.toInt()
                dark -> 0xFF3A3F44.toInt()
                else -> 0xFFF2F2F7.toInt()
            }
            bolt.color = if (dark || (!charging && level <= 15)) 0xFFFFFFFF.toInt() else 0xFF2C2C2E.toInt()
        }

        rect.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        canvas.drawPath(clip, track)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w * level.coerceIn(0, 100) / 100f, h, fill)
        canvas.restore()

        if (charging) {
            val bh = h * 0.72f; val bw = bh * 0.62f
            val l = (w - bw) / 2f; val t = (h - bh) / 2f
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
    }
}
