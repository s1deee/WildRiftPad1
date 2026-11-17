package app.wrpad

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var cfg: PadConfig
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = PadConfig.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        root.add(label("WildRiftPad", 26f, bold = true))
        status = label("", 16f)
        root.add(status)

        root.add(button("1. Включить службу в спец. возможностях") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.add(button("Пункт серый? Открыть «О приложении»") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
            Toast.makeText(
                this,
                "Меню ⋮ → «Разрешить ограниченные настройки», потом снова включите службу",
                Toast.LENGTH_LONG,
            ).show()
        })

        root.add(sw("Раскладка включена", cfg.enabled) { cfg.enabled = it })
        root.add(sw("Работать в любом приложении (для проверки)", cfg.anyApp) { cfg.anyApp = it })

        root.add(seek("Радиус прицеливания", "%", 5, 30, (cfg.aimRadius * 100).toInt()) {
            cfg.aimRadius = it / 100f
        })
        root.add(seek("Мёртвая зона стиков", "%", 5, 50, (cfg.deadzone * 100).toInt()) {
            cfg.deadzone = it / 100f
        })
        root.add(seek("Длина куска касания", " мс", 30, 120, cfg.sliceMs) { cfg.sliceMs = it })

        root.add(button("2. Редактор раскладки (откроется через 6 сек)") {
            if (PadService.instance == null) {
                Toast.makeText(this, "Сначала включите службу", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Откройте Wild Rift, редактор появится поверх", Toast.LENGTH_LONG).show()
                ui.postDelayed({ PadService.instance?.showEditor() }, 6000)
                moveTaskToBack(true)
            }
        })
        root.add(button("Сбросить раскладку") {
            cfg.resetLayout()
            cfg.save(this)
            Toast.makeText(this, "Раскладка сброшена", Toast.LENGTH_SHORT).show()
        })

        debug = label("", 14f)
        debug.setTextIsSelectable(true)
        root.add(debug)

        root.add(
            label(
                "Как пользоваться\n" +
                    "• Подключите геймпад и включите службу.\n" +
                    "• Откройте Wild Rift, зайдите в тренировку и откройте редактор " +
                    "(кнопки Select + Start на геймпаде или кнопка выше). " +
                    "Совместите кружки с кнопками игры и нажмите «Готово».\n" +
                    "• Левый стик: движение. Кнопки: навыки, атака, заклинания.\n" +
                    "• Держите навык и двигайте правый стик: прицеливание, отпустили: каст.\n" +
                    "• Если стики не реагируют, строка выше покажет, что приходит от геймпада.",
                14f,
            ),
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    private fun refresh() {
        status.text = if (PadService.instance != null) {
            "Служба: работает ✔"
        } else {
            "Служба: выключена ✘ (включите в спец. возможностях)"
        }
        debug.text = "Последнее событие геймпада:\n" + PadService.lastDebug
    }

    // ------------------------------------------------------------ ui helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun LinearLayout.add(v: View) {
        addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
    }

    private fun label(t: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun sw(t: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        isChecked = on
        setOnCheckedChangeListener { _, v ->
            change(v)
            cfg.save(this@MainActivity)
            PadService.instance?.onConfigChanged()
        }
    }

    private fun seek(
        title: String,
        unit: String,
        lo: Int,
        hi: Int,
        value: Int,
        onChange: (Int) -> Unit,
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply { text = "$title: ${value.coerceIn(lo, hi)}$unit" }
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = value.coerceIn(lo, hi) - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    tv.text = "$title: $v$unit"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) {
                    cfg.save(this@MainActivity)
                }
            })
        }
        box.addView(tv)
        box.addView(sb)
        return box
    }
}
