package app.wrpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Держит несколько «пальцев» одновременно через dispatchGesture.
 *
 * Система позволяет выполнять только один жест за раз, а новый жест отменяет текущий.
 * Поэтому все удерживаемые пальцы каждый раз отправляются вместе, короткими кусками
 * (slice), и каждый кусок продолжает предыдущий через continueStroke.
 * Следующий кусок уходит сразу после завершения предыдущего.
 */
class TouchInjector(
    private val service: AccessibilityService,
    private val sliceMs: () -> Long,
) {
    private class Contact(var x: Float, var y: Float) {
        var lastX = x
        var lastY = y
        var stroke: StrokeDescription? = null
        var releaseRequested = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val contacts = LinkedHashMap<Int, Contact>()
    private var nextToken = 1
    private var busy = false
    private var scheduled = false
    private var finalInFlight: List<Int> = emptyList()

    private val pumpRunnable = Runnable {
        scheduled = false
        pump()
    }

    /** Начать касание. Возвращает токен, по которому касание двигают и отпускают. */
    fun down(x: Float, y: Float): Int {
        val (w, h) = bounds()
        val t = nextToken++
        contacts[t] = Contact(x.coerceIn(0f, w - 1f), y.coerceIn(0f, h - 1f))
        schedule()
        return t
    }

    fun move(token: Int, x: Float, y: Float) {
        val c = contacts[token] ?: return
        if (c.releaseRequested) return
        c.x = x
        c.y = y
        schedule()
    }

    fun up(token: Int) {
        val c = contacts[token] ?: return
        c.releaseRequested = true
        schedule()
    }

    private fun schedule(delayMs: Long = 0L) {
        if (busy || scheduled) return
        scheduled = true
        handler.postDelayed(pumpRunnable, delayMs)
    }

    private fun bounds(): Pair<Float, Float> {
        val b = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Pair(b.width().toFloat(), b.height().toFloat())
    }

    private fun pump() {
        if (busy || contacts.isEmpty()) return
        val (w, h) = bounds()
        val slice = sliceMs().coerceAtLeast(10L)
        val builder = GestureDescription.Builder()
        val finals = ArrayList<Int>()
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        var count = 0

        for ((token, c) in contacts) {
            if (count >= maxStrokes) break
            val nx = c.x.coerceIn(0f, w - 1f)
            val ny = c.y.coerceIn(0f, h - 1f)
            val path = Path()
            path.moveTo(c.lastX, c.lastY)
            if (nx != c.lastX || ny != c.lastY) path.lineTo(nx, ny)
            val willContinue = !c.releaseRequested
            val prev = c.stroke
            val s = if (prev == null) {
                StrokeDescription(path, 0L, slice, willContinue)
            } else {
                prev.continueStroke(path, 0L, slice, willContinue)
            }
            builder.addStroke(s)
            c.stroke = s
            c.lastX = nx
            c.lastY = ny
            if (!willContinue) finals.add(token)
            count++
        }

        finalInFlight = finals
        busy = true
        val ok = try {
            service.dispatchGesture(builder.build(), callback, handler)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            busy = false
            finalInFlight = emptyList()
            contacts.clear()
        }
    }

    private val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            busy = false
            for (t in finalInFlight) contacts.remove(t)
            finalInFlight = emptyList()
            if (contacts.isNotEmpty()) pump()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            busy = false
            for (t in finalInFlight) contacts.remove(t)
            finalInFlight = emptyList()
            // Цепочка прервана: удерживаемые пальцы начинаем заново с текущей позиции.
            val it = contacts.entries.iterator()
            while (it.hasNext()) {
                val c = it.next().value
                if (c.releaseRequested) {
                    it.remove()
                } else {
                    c.stroke = null
                    c.lastX = c.x
                    c.lastY = c.y
                }
            }
            if (contacts.isNotEmpty()) schedule(40L)
        }
    }
}
