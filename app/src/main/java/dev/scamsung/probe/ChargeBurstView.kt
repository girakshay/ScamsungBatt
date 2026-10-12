package dev.scamsung.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Covers Samsung's "charger connected" animation: a wide green gradient capsule with a bolt and no digits.
 * [phase] (0..1) slides the gradient so the fill shimmers like Samsung's.
 */
class ChargeBurstView(c: Context) : View(c) {
    var level = 100
    var phase = 0f
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7D848B.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()
    private val clip = Path()
    private val boltPath = Path()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shader = LinearGradient(
            0f, 0f, w * 1.5f, 0f,
            intArrayOf(0xFF39F889.toInt(), 0xFF8EF37C.toInt(), 0xFF2BDA95.toInt()), null, Shader.TileMode.MIRROR,
        )
        fill.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        canvas.drawPath(clip, track)
        canvas.save()
        canvas.clipPath(clip)
        matrix.setTranslate(phase * w * 1.5f, 0f)
        shader?.setLocalMatrix(matrix)
        canvas.drawRect(0f, 0f, w * level.coerceIn(0, 100) / 100f, h, fill)
        canvas.restore()

        val bh = h * 0.56f; val bw = bh * 0.62f
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
