package com.travessia.segura.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.travessia.segura.config.AppConfig
import com.travessia.segura.benchmark.BenchmarkLogger
import com.travessia.segura.config.LanguageManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: AppConfig
    private lateinit var container: LinearLayout

    private val dp: Float
        get() = resources.displayMetrics.density

    private fun tr(key: String): String = LanguageManager.text(this, key)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#050B18")
        WindowCompat.setDecorFitsSystemWindows(window, false)

        config = AppConfig(this)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#050B18"))
            setPadding(dpInt(18), dpInt(18), dpInt(18), dpInt(24))
            clipToPadding = false
        }

        val baseLeft = scrollView.paddingLeft
        val baseTop = scrollView.paddingTop
        val baseRight = scrollView.paddingRight
        val baseBottom = scrollView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        addHeader()

        addSection(tr("settings_language_section"), tr("settings_language_subtitle"))
        addLanguageSpinner()

        addSection(tr("settings_accessibility_section"), tr("settings_accessibility_subtitle"))
        addSwitch(tr("settings_voice_commands"), config.voiceCommandsEnabled) {
            config.voiceCommandsEnabled = it
        }
        addSwitch(tr("settings_voice_auto_listen"), config.voiceAutoListen) {
            config.voiceAutoListen = it
        }

        addSection(tr("settings_appearance_section"), tr("settings_appearance_subtitle"))
        addSwitch(tr("settings_esp_fullscreen"), config.espPreviewTelaCheia) {
            config.espPreviewTelaCheia = it
        }
        addSpinnerInt(tr("settings_esp_rotation"), config.espPreviewRotation, listOf(0, 90, 180, 270)) {
            config.espPreviewRotation = it
        }
        addSwitch(tr("settings_show_boxes"), config.mostrarOverlay) {
            config.mostrarOverlay = it
        }
        addSwitch(tr("settings_show_top_panel"), config.mostrarPainelSuperior) {
            config.mostrarPainelSuperior = it
        }
        addSwitch(tr("settings_show_bottom_panel"), config.mostrarPainelInferior) {
            config.mostrarPainelInferior = it
        }

        addSection(tr("settings_ai_section"), tr("settings_ai_subtitle"))
        addSliderFloat(tr("settings_confidence"), config.confMinima, 0.05f, 0.95f, 0.05f) {
            config.confMinima = it
        }
        addSpinnerInt(tr("settings_inference_size"), config.inferenciaTamanho, listOf(160, 224, 320, 416, 640)) {
            config.inferenciaTamanho = it
        }
        addSliderInt(tr("settings_history"), config.historicoMax, 5, 60) {
            config.historicoMax = it
        }

        addSection(tr("settings_tracking_section"), tr("settings_tracking_subtitle"))
        addSliderInt(tr("settings_ghost_frames"), config.trackerGhostFrames, 0, 8) {
            config.trackerGhostFrames = it
        }
        addSliderFloat(
            tr("settings_center_tolerance"),
            config.trackerCenterDistancePct,
            0.08f, 0.30f, 0.01f
        ) {
            config.trackerCenterDistancePct = it
        }
        addSliderFloat(
            tr("settings_bbox_growth"),
            config.crescimentoBBoxAproximando,
            0.06f, 0.35f, 0.01f
        ) {
            config.crescimentoBBoxAproximando = it
        }

        addSection(tr("settings_vision_section"), tr("settings_vision_subtitle"))
        addSwitch(tr("settings_optical_flow"), config.opticalFlowEnabled) {
            config.opticalFlowEnabled = it
        }
        addSliderFloat(
            tr("settings_optical_flow_confidence"),
            config.opticalFlowMinConfidence,
            0.10f, 0.90f, 0.05f
        ) {
            config.opticalFlowMinConfidence = it
        }
        addSwitch(tr("settings_benchmark"), config.benchmarkEnabled) {
            config.benchmarkEnabled = it
        }
        addActionButton(tr("settings_share_benchmark")) {
            if (!BenchmarkLogger.shareLatest(this@SettingsActivity)) {
                Toast.makeText(this@SettingsActivity, tr("settings_no_benchmark"), Toast.LENGTH_SHORT).show()
            }
        }

        addSection(tr("settings_traffic_section"), tr("settings_traffic_subtitle"))
        addSliderFloat(tr("settings_stopped_speed"), config.velParadoPxs, 5f, 50f, 1f) {
            config.velParadoPxs = it
        }
        addSliderFloat(tr("settings_approaching_speed"), config.velAproximandoPxs, 10f, 80f, 1f) {
            config.velAproximandoPxs = it
        }
        addSliderFloat(tr("settings_proximity"), config.limiarProximoPct, 0.10f, 0.60f, 0.02f) {
            config.limiarProximoPct = it
        }

        addSection(tr("settings_time_section"), tr("settings_time_subtitle"))
        addSliderFloat(tr("settings_recognition_time"), config.tempoRecon, 1f, 10f, 0.5f) {
            config.tempoRecon = it
        }
        addSliderFloat(tr("settings_safe_stop_time"), config.tempoParadoSeguro, 1f, 10f, 0.5f) {
            config.tempoParadoSeguro = it
        }
        addSliderFloat(tr("settings_recon_threshold"), config.reconThreshold, 0.05f, 0.80f, 0.05f) {
            config.reconThreshold = it
        }
        addSliderFloat(tr("settings_recon_early_threshold"), config.reconEarlyThreshold, 0.10f, 0.90f, 0.05f) {
            config.reconEarlyThreshold = it
        }
        addSliderInt(tr("settings_recon_early_min_frames"), config.reconEarlyMinFrames, 5, 60) {
            config.reconEarlyMinFrames = it
        }
        addSliderInt(tr("settings_upgrade_frames"), config.upgradeFrames, 3, 30) {
            config.upgradeFrames = it
        }

        addSection(tr("settings_roi_section"), tr("settings_roi_subtitle"))
        addSwitch(tr("settings_roi_active"), config.roiAtivo) {
            config.roiAtivo = it
        }
        addSliderFloat(tr("settings_roi_top"), config.roiTop, 0f, 0.50f, 0.01f) {
            config.roiTop = it
        }
        addSliderFloat(tr("settings_roi_bottom"), config.roiBottom, 0.50f, 1.0f, 0.01f) {
            config.roiBottom = it
        }
        addSliderFloat(tr("settings_roi_left"), config.roiLeft, 0f, 0.30f, 0.01f) {
            config.roiLeft = it
        }
        addSliderFloat(tr("settings_roi_right"), config.roiRight, 0.70f, 1.0f, 0.01f) {
            config.roiRight = it
        }

        addSection(tr("settings_smoothing_section"), tr("settings_smoothing_subtitle"))
        addSliderFloat(tr("settings_ema"), config.emaAlpha, 0.10f, 0.90f, 0.05f) {
            config.emaAlpha = it
        }
        addSliderFloat(tr("settings_crosswalk_margin"), config.faixaMargemAlinhamento, 0.02f, 0.30f, 0.01f) {
            config.faixaMargemAlinhamento = it
        }
        addSliderFloat(tr("settings_heartbeat"), config.heartbeatInterval, 2f, 15f, 0.5f) {
            config.heartbeatInterval = it
        }
        addSliderFloat(tr("settings_voice_volume"), config.volumeVoz, 0.1f, 1.0f, 0.1f) {
            config.volumeVoz = it
        }

        addResetButton()

        scrollView.addView(container)
        setContentView(scrollView)
        ViewCompat.requestApplyInsets(scrollView)
    }

    private fun addHeader() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground("#12263A", "#1B4A6B", 28f)
            setPadding(dpInt(20), dpInt(20), dpInt(20), dpInt(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(18)
            }
        }

        card.addView(TextView(this).apply {
            text = tr("settings_title")
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })

        card.addView(TextView(this).apply {
            text = tr("settings_subtitle")
            textSize = 14f
            setTextColor(Color.parseColor("#CDE7F4FF"))
            setPadding(0, dpInt(6), 0, 0)
        })

        container.addView(card)
    }

    private fun addSection(title: String, subtitle: String = "") {
        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#63D8FF"))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dpInt(18), 0, dpInt(4))
        }
        container.addView(titleView)

        if (subtitle.isNotBlank()) {
            container.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#9CB6C8"))
                setPadding(0, 0, 0, dpInt(8))
            })
        }
    }

    private fun addLanguageSpinner() {
        val options = LanguageManager.options
        val current = config.appLanguage

        val box = controlBox()
        box.addView(TextView(this).apply {
            text = tr("settings_language")
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dpInt(8))
        })

        var firstSelection = true
        val spinner = Spinner(this).apply {
            contentDescription = tr("settings_language")
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.label }
            )
            setSelection(options.indexOfFirst { it.code == current }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (firstSelection) {
                        firstSelection = false
                        return
                    }

                    val code = options[position].code
                    if (code != config.appLanguage) {
                        config.appLanguage = code
                        LanguageManager.setLanguage(this@SettingsActivity, code)
                        Toast.makeText(this@SettingsActivity, tr("settings_language_saved"), Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        box.addView(spinner)
        container.addView(box)
    }

    private fun addSliderFloat(
        label: String,
        initialValue: Float,
        min: Float,
        max: Float,
        step: Float,
        onChange: (Float) -> Unit
    ) {
        val steps = ((max - min) / step).toInt()
        val initialProgress = ((initialValue - min) / step).toInt().coerceIn(0, steps)

        val box = controlBox()
        val tvLabel = TextView(this).apply {
            text = "$label: %.2f".format(initialValue)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        box.addView(tvLabel)

        val seekBar = SeekBar(this).apply {
            contentDescription = label
            this.max = steps
            progress = initialProgress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = min + progress * step
                    tvLabel.text = "$label: %.2f".format(value)
                    onChange(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seekBar)
        container.addView(box)
    }

    private fun addSliderInt(label: String, initialValue: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
        val box = controlBox()
        val tvLabel = TextView(this).apply {
            text = "$label: $initialValue"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        box.addView(tvLabel)

        val seekBar = SeekBar(this).apply {
            contentDescription = label
            this.max = max - min
            progress = initialValue - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = min + progress
                    tvLabel.text = "$label: $value"
                    onChange(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seekBar)
        container.addView(box)
    }

    private fun addSpinnerInt(label: String, initialValue: Int, options: List<Int>, onChange: (Int) -> Unit) {
        val box = controlBox()
        box.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dpInt(8))
        })

        val spinner = Spinner(this).apply {
            contentDescription = label
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.toString() }
            )
            setSelection(options.indexOf(initialValue).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onChange(options[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        box.addView(spinner)
        container.addView(box)
    }

    private fun addSwitch(label: String, initialValue: Boolean, onChange: (Boolean) -> Unit) {
        val box = controlBox()
        val switch = Switch(this).apply {
            text = label
            isChecked = initialValue
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        box.addView(switch)
        container.addView(box)
    }

    private fun addActionButton(label: String, onClick: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            contentDescription = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = roundedBackground("#16324A", "#1B5E7A", 18f)
            setOnClickListener { onClick() }
        }
        container.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpInt(50)
        ).apply {
            topMargin = dpInt(8)
        })
    }

    private fun addResetButton() {
        val btnReset = Button(this).apply {
            text = tr("settings_reset")
            contentDescription = tr("settings_reset")
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = roundedBackground("#B71C1C", "#D32F2F", 18f)
            setOnClickListener {
                config.resetarParaPadrao()
                Toast.makeText(this@SettingsActivity, tr("settings_reset_done"), Toast.LENGTH_SHORT).show()
                recreate()
            }
        }
        container.addView(btnReset, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpInt(54)
        ).apply {
            topMargin = dpInt(24)
        })
    }

    private fun controlBox(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground("#101C2B", "#1C3348", 18f)
            setPadding(dpInt(14), dpInt(12), dpInt(14), dpInt(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpInt(8)
            }
        }
    }

    private fun roundedBackground(color1: String, color2: String, radiusDp: Float): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(color1), Color.parseColor(color2))
        ).apply {
            cornerRadius = radiusDp * dp
            setStroke(dpInt(1), Color.parseColor("#30FFFFFF"))
        }
    }

    private fun dpInt(value: Int): Int = (value * dp).toInt()
}
