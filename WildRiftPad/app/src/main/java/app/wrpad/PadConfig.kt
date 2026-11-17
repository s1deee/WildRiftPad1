package app.wrpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Виртуальные кнопки геймпада, которые можно привязать к точке на экране. */
enum class VBtn(val short: String, val title: String, val aims: Boolean) {
    A("A", "Навык 1", true),
    X("X", "Навык 2", true),
    Y("Y", "Навык 3", true),
    B("B", "Ульта", true),
    R1("R1", "Закл. 1", true),
    L1("L1", "Закл. 2", true),
    R2("R2", "Атака", false),
    L2("L2", "Возврат", false),
    R3("R3", "Цель", false),
    L3("L3", "Сигнал", false),
    UP("↑", "Магазин", false),
    DOWN("↓", "Предмет 1", false),
    LEFT("←", "Предмет 2", false),
    RIGHT("→", "Предмет 3", false),
}

/**
 * Настройки и раскладка. Все координаты хранятся как доли экрана (0..1),
 * поэтому не зависят от разрешения. Предполагается альбомная ориентация.
 */
class PadConfig {
    var enabled = true
    var anyApp = false
    var deadzone = 0.25f
    var aimRadius = 0.14f   // доля от меньшей стороны экрана
    var moveRadius = 0.12f  // доля от меньшей стороны экрана
    var sliceMs = 50
    var moveX = 0.14f
    var moveY = 0.68f
    val pts = LinkedHashMap<VBtn, FloatArray>()

    init {
        resetLayout()
    }

    /** Примерные стартовые позиции. Их нужно подогнать под ваш HUD в редакторе. */
    fun resetLayout() {
        moveX = 0.14f
        moveY = 0.68f
        moveRadius = 0.12f
        pts.clear()
        fun p(b: VBtn, x: Float, y: Float) {
            pts[b] = floatArrayOf(x, y)
        }
        p(VBtn.R2, 0.88f, 0.76f)
        p(VBtn.A, 0.76f, 0.88f)
        p(VBtn.X, 0.79f, 0.70f)
        p(VBtn.Y, 0.86f, 0.57f)
        p(VBtn.B, 0.93f, 0.57f)
        p(VBtn.R1, 0.70f, 0.60f)
        p(VBtn.L1, 0.70f, 0.48f)
        p(VBtn.L2, 0.62f, 0.88f)
        p(VBtn.R3, 0.95f, 0.88f)
        p(VBtn.L3, 0.50f, 0.15f)
        p(VBtn.UP, 0.50f, 0.05f)
        p(VBtn.DOWN, 0.55f, 0.92f)
        p(VBtn.LEFT, 0.45f, 0.92f)
        p(VBtn.RIGHT, 0.65f, 0.92f)
    }

    fun applyLayoutFrom(o: PadConfig) {
        moveX = o.moveX
        moveY = o.moveY
        moveRadius = o.moveRadius
        pts.clear()
        for ((b, v) in o.pts) pts[b] = v.copyOf()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("anyApp", anyApp)
        o.put("deadzone", deadzone.toDouble())
        o.put("aimRadius", aimRadius.toDouble())
        o.put("moveRadius", moveRadius.toDouble())
        o.put("sliceMs", sliceMs)
        o.put("moveX", moveX.toDouble())
        o.put("moveY", moveY.toDouble())
        val p = JSONObject()
        for ((b, v) in pts) {
            p.put(b.name, JSONArray().put(v[0].toDouble()).put(v[1].toDouble()))
        }
        o.put("pts", p)
        return o
    }

    fun copy(): PadConfig = fromJson(toJson())

    fun save(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("wrpad", Context.MODE_PRIVATE)
            .edit().putString("cfg", toJson().toString()).apply()
    }

    companion object {
        @Volatile
        private var inst: PadConfig? = null

        /** Один общий объект на процесс: его используют и сервис, и экран настроек. */
        fun get(ctx: Context): PadConfig =
            inst ?: synchronized(this) {
                inst ?: load(ctx.applicationContext).also { inst = it }
            }

        private fun load(ctx: Context): PadConfig {
            val s = ctx.getSharedPreferences("wrpad", Context.MODE_PRIVATE).getString("cfg", null)
                ?: return PadConfig()
            return try {
                fromJson(JSONObject(s))
            } catch (e: Exception) {
                PadConfig()
            }
        }

        fun fromJson(o: JSONObject): PadConfig {
            val c = PadConfig()
            c.enabled = o.optBoolean("enabled", true)
            c.anyApp = o.optBoolean("anyApp", false)
            c.deadzone = o.optDouble("deadzone", 0.25).toFloat()
            c.aimRadius = o.optDouble("aimRadius", 0.14).toFloat()
            c.moveRadius = o.optDouble("moveRadius", 0.12).toFloat()
            c.sliceMs = o.optInt("sliceMs", 50)
            c.moveX = o.optDouble("moveX", 0.14).toFloat()
            c.moveY = o.optDouble("moveY", 0.68).toFloat()
            val p = o.optJSONObject("pts")
            if (p != null) {
                for (b in VBtn.values()) {
                    val a = p.optJSONArray(b.name) ?: continue
                    if (a.length() == 2) {
                        c.pts[b] = floatArrayOf(a.optDouble(0).toFloat(), a.optDouble(1).toFloat())
                    }
                }
            }
            return c
        }
    }
}
