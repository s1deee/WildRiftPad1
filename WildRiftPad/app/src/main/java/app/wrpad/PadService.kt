package app.wrpad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class PadService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PadService? = null

        @Volatile
        var lastDebug: String = "пока ничего не приходило"

        /** Wild Rift (все региональные версии Riot) и китайская версия от Tencent. */
        fun isGamePackage(p: String) =
            p.startsWith("com.riotgames.league.wildrift") || p == "com.tencent.lolm"
    }

    private lateinit var cfg: PadConfig
    private lateinit var injector: TouchInjector

    private var currentPkg = ""
    private val held = HashMap<VBtn, Int>()
    private val aimOrder = ArrayList<VBtn>()
    private var moveToken = -1
    private var rightX = 0f
    private var rightY = 0f
    private var l2Down = false
    private var r2Down = false
    private var triggersAsKeys = false
    private var dpadAsKeys = false
    private var selectDown = false
    private var startDown = false
    private var comboLatched = false
    private var lastDebugAt = 0L
    private var editor: EditorView? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cfg = PadConfig.get(this)
        injector = TouchInjector(this) { cfg.sliceMs.toLong() }

        // Android 14+: просим систему отдавать сюда движения стиков.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val info = serviceInfo
                info.motionEventSources = InputDevice.SOURCE_JOYSTICK
                serviceInfo = info
            } catch (t: Throwable) {
                lastDebug = "не удалось включить приём стиков: ${t.message}"
            }
        }
    }

    override fun onInterrupt() {
        releaseAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (::injector.isInitialized) releaseAll()
        closeEditor()
        if (instance === this) instance = null
    }

    // ---------------------------------------------------------------- context

    private fun imePackage(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') ?: ""

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == packageName || p.startsWith("com.android.systemui") || p == imePackage()) return
        if (p != currentPkg) {
            currentPkg = p
            if (!isActive()) releaseAll()
        }
    }

    private fun inGame() = cfg.enabled && (cfg.anyApp || isGamePackage(currentPkg))

    private fun isActive() = inGame() && editor == null

    /** Вызывается из экрана настроек, когда пользователь поменял переключатели. */
    fun onConfigChanged() {
        if (!isActive()) releaseAll()
    }

    private fun screenSize(): Pair<Float, Float> {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Pair(b.width().toFloat(), b.height().toFloat())
    }

    // ---------------------------------------------------------------- buttons

    private fun mapKey(code: Int): VBtn? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> VBtn.A
        KeyEvent.KEYCODE_BUTTON_B -> VBtn.B
        KeyEvent.KEYCODE_BUTTON_X -> VBtn.X
        KeyEvent.KEYCODE_BUTTON_Y -> VBtn.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> VBtn.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> VBtn.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> VBtn.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> VBtn.R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> VBtn.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> VBtn.R3
        KeyEvent.KEYCODE_DPAD_UP -> VBtn.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> VBtn.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> VBtn.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> VBtn.RIGHT
        else -> null
    }

    private fun isFromPad(e: KeyEvent): Boolean =
        e.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            e.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            (e.isFromSource(InputDevice.SOURCE_DPAD) && !e.isFromSource(InputDevice.SOURCE_KEYBOARD))

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isFromPad(event)) return false
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        lastDebug = "${KeyEvent.keyCodeToString(code)} ${if (down) "нажата" else "отпущена"}"

        // Select + Start вместе: открыть/закрыть редактор раскладки.
        val isSelect = code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BACK
        val isStart = code == KeyEvent.KEYCODE_BUTTON_START
        if (isSelect || isStart) {
            if (!inGame()) return false
            if (event.repeatCount == 0) {
                if (isSelect) selectDown = down else startDown = down
                if (down && selectDown && startDown && !comboLatched) {
                    comboLatched = true
                    toggleEditor()
                }
                if (!selectDown && !startDown) comboLatched = false
            }
            return true
        }

        if (!isActive()) return false
        val b = mapKey(code) ?: return false
        if (b == VBtn.L2 || b == VBtn.R2) triggersAsKeys = true
        if (b == VBtn.UP || b == VBtn.DOWN || b == VBtn.LEFT || b == VBtn.RIGHT) dpadAsKeys = true

        if (down) {
            if (event.repeatCount == 0) press(b)
        } else if (event.action == KeyEvent.ACTION_UP) {
            release(b)
        }
        return true
    }

    private fun press(b: VBtn) {
        if (held.containsKey(b)) return
        val p = cfg.pts[b] ?: return
        val (w, h) = screenSize()
        held[b] = injector.down(p[0] * w, p[1] * h)
        if (b.aims) {
            aimOrder.remove(b)
            aimOrder.add(b)
            applyAim()
        }
    }

    private fun release(b: VBtn) {
        val t = held.remove(b) ?: return
        aimOrder.remove(b)
        injector.up(t)
    }

    private fun setHeld(b: VBtn, want: Boolean) {
        val has = held.containsKey(b)
        if (want && !has) press(b) else if (!want && has) release(b)
    }

    /** Правый стик сдвигает палец вокруг кнопки последнего удерживаемого навыка. */
    private fun applyAim() {
        val b = aimOrder.lastOrNull() ?: return
        val t = held[b] ?: return
        val p = cfg.pts[b] ?: return
        val (w, h) = screenSize()
        val r = cfg.aimRadius * min(w, h)
        injector.move(t, p[0] * w + rightX * r, p[1] * h + rightY * r)
    }

    private fun releaseAll() {
        for (t in held.values) injector.up(t)
        held.clear()
        aimOrder.clear()
        if (moveToken >= 0) {
            injector.up(moveToken)
            moveToken = -1
        }
        l2Down = false
        r2Down = false
        rightX = 0f
        rightY = 0f
    }

    // ---------------------------------------------------------------- sticks

    private fun stickVec(x: Float, y: Float): FloatArray {
        val m = hypot(x, y)
        if (m <= cfg.deadzone) return floatArrayOf(0f, 0f)
        val k = min(1f, (m - cfg.deadzone) / (1f - cfg.deadzone)) / m
        return floatArrayOf(x * k, y * k)
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return

        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        var rx = event.getAxisValue(MotionEvent.AXIS_Z)
        var ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (abs(rx) < 0.01f && abs(ry) < 0.01f) {
            rx = event.getAxisValue(MotionEvent.AXIS_RX)
            ry = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        val lt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        val rt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        )
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt()
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt()

        val now = SystemClock.uptimeMillis()
        if (now - lastDebugAt > 150) {
            lastDebugAt = now
            lastDebug = "стики: L(%.2f; %.2f) R(%.2f; %.2f) LT %.2f RT %.2f hat(%d; %d)"
                .format(lx, ly, rx, ry, lt, rt, hx, hy)
        }

        if (!isActive()) return

        // Левый стик: движение.
        val lv = stickVec(lx, ly)
        if (lv[0] != 0f || lv[1] != 0f) {
            val (w, h) = screenSize()
            val r = cfg.moveRadius * min(w, h)
            val cx = cfg.moveX * w
            val cy = cfg.moveY * h
            if (moveToken < 0) moveToken = injector.down(cx, cy)
            injector.move(moveToken, cx + lv[0] * r, cy + lv[1] * r)
        } else if (moveToken >= 0) {
            injector.up(moveToken)
            moveToken = -1
        }

        // Правый стик: прицеливание удерживаемого навыка.
        val rv = stickVec(rx, ry)
        if (rv[0] != rightX || rv[1] != rightY) {
            rightX = rv[0]
            rightY = rv[1]
            applyAim()
        }

        // Триггеры, если геймпад отдаёт их осями, а не кнопками.
        if (!triggersAsKeys) {
            val l2 = if (l2Down) lt > 0.3f else lt > 0.6f
            if (l2 != l2Down) {
                l2Down = l2
                setHeld(VBtn.L2, l2)
            }
            val r2 = if (r2Down) rt > 0.3f else rt > 0.6f
            if (r2 != r2Down) {
                r2Down = r2
                setHeld(VBtn.R2, r2)
            }
        }

        // Крестовина, если она приходит как hat-ось.
        if (!dpadAsKeys) {
            setHeld(VBtn.LEFT, hx < 0)
            setHeld(VBtn.RIGHT, hx > 0)
            setHeld(VBtn.UP, hy < 0)
            setHeld(VBtn.DOWN, hy > 0)
        }
    }

    // ---------------------------------------------------------------- editor

    fun toggleEditor() {
        val e = editor
        if (e == null) showEditor() else e.save()
    }

    fun showEditor() {
        if (editor != null) return
        releaseAll()
        val view = EditorView(
            this,
            cfg.copy(),
            onSave = { edited ->
                cfg.applyLayoutFrom(edited)
                cfg.save(this)
                closeEditor()
            },
            onCancel = { closeEditor() },
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        getSystemService(WindowManager::class.java).addView(view, lp)
        editor = view
    }

    private fun closeEditor() {
        val v = editor ?: return
        editor = null
        try {
            getSystemService(WindowManager::class.java).removeView(v)
        } catch (e: Exception) {
            // окно уже убрано
        }
    }
}
