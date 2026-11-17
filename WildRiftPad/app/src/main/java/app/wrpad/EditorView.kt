package app.wrpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.min

/**
 * Полупрозрачный редактор поверх игры: перетаскиваете кружки на кнопки HUD.
 * Работает с копией настроек, в сервис попадает только после «Готово».
 */
class EditorView(
    context: Context,
    private val work: PadConfig,
    private val onSave: (PadConfig) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private class Drag(val kind: Int, val btn: VBtn?) // 0 центр стика, 1 радиус стика, 2 кнопка

    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xDD1E88E5.toInt()
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Color.WHITE
    }
    private val moveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x5500E676
    }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xEE263238.toInt()
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val loc = IntArray(2)
    private var screenW = 1f
    private var screenH = 1f
    private val barRects = arrayOf(RectF(), RectF(), RectF())
    private val barLabels = arrayOf("Готово", "Сброс", "Отмена")
    private var drag: Drag? = null

    init {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        screenW = b.width().toFloat()
        screenH = b.height().toFloat()
    }

    fun save() = onSave(work)

    private fun minDim() = min(screenW, screenH)

    private fun layoutBar() {
        val bw = 110f * d
        val bh = 44f * d
        val gap = 10f * d
        val total = 3 * bw + 2 * gap
        val left = (width - total) / 2f
        val top = 10f * d
        for (i in 0..2) {
            val l = left + i * (bw + gap)
            barRects[i].set(l, top, l + bw, top + bh)
        }
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)

        // Зона стика движения.
        val cx = work.moveX * screenW - loc[0]
        val cy = work.moveY * screenH - loc[1]
        val r = work.moveRadius * minDim()
        c.drawCircle(cx, cy, r, moveFill)
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, 10f * d, fill)
        txt.textSize = 12f * d
        c.drawText("Стик движения", cx, cy - 16f * d, txt)
        c.drawCircle(cx + r, cy, 13f * d, fill)
        c.drawCircle(cx + r, cy, 13f * d, ring)
        txt.textSize = 12f * d
        c.drawText("R", cx + r, cy + 4f * d, txt)

        // Кнопки.
        for ((b, p) in work.pts) {
            val x = p[0] * screenW - loc[0]
            val y = p[1] * screenH - loc[1]
            c.drawCircle(x, y, 22f * d, fill)
            c.drawCircle(x, y, 22f * d, ring)
            txt.textSize = 14f * d
            c.drawText(b.short, x, y + 5f * d, txt)
            txt.textSize = 10f * d
            c.drawText(b.title, x, y + 37f * d, txt)
        }

        // Верхняя панель.
        layoutBar()
        for (i in 0..2) {
            c.drawRoundRect(barRects[i], 10f * d, 10f * d, barFill)
            c.drawRoundRect(barRects[i], 10f * d, 10f * d, ring)
            txt.textSize = 15f * d
            c.drawText(barLabels[i], barRects[i].centerX(), barRects[i].centerY() + 5f * d, txt)
        }
        txt.textSize = 12f * d
        c.drawText(
            "Перетащите кружки на кнопки игры. Ручка R меняет размер зоны стика.",
            width / 2f, barRects[0].bottom + 22f * d, txt,
        )
    }

    private fun pick(x: Float, y: Float): Drag? {
        val gx = x + loc[0]
        val gy = y + loc[1]
        var best: Drag? = null
        var bestD = 48f * d
        fun consider(px: Float, py: Float, cand: Drag) {
            val dd = hypot(gx - px, gy - py)
            if (dd < bestD) {
                bestD = dd
                best = cand
            }
        }
        consider(work.moveX * screenW, work.moveY * screenH, Drag(0, null))
        consider(work.moveX * screenW + work.moveRadius * minDim(), work.moveY * screenH, Drag(1, null))
        for ((b, p) in work.pts) consider(p[0] * screenW, p[1] * screenH, Drag(2, b))
        return best
    }

    private fun applyDrag(dr: Drag, x: Float, y: Float) {
        val gx = x + loc[0]
        val gy = y + loc[1]
        when (dr.kind) {
            0 -> {
                work.moveX = (gx / screenW).coerceIn(0f, 1f)
                work.moveY = (gy / screenH).coerceIn(0f, 1f)
            }
            1 -> {
                val dx = gx - work.moveX * screenW
                val dy = gy - work.moveY * screenH
                work.moveRadius = (hypot(dx, dy) / minDim()).coerceIn(0.04f, 0.30f)
            }
            else -> {
                val b = dr.btn ?: return
                work.pts[b] = floatArrayOf(
                    (gx / screenW).coerceIn(0f, 1f),
                    (gy / screenH).coerceIn(0f, 1f),
                )
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        getLocationOnScreen(loc)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layoutBar()
                for (i in 0..2) {
                    if (barRects[i].contains(e.x, e.y)) {
                        when (i) {
                            0 -> onSave(work)
                            1 -> {
                                work.resetLayout()
                                invalidate()
                            }
                            else -> onCancel()
                        }
                        return true
                    }
                }
                drag = pick(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dr = drag
                if (dr != null) {
                    applyDrag(dr, e.x, e.y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = null
        }
        return true
    }
}
