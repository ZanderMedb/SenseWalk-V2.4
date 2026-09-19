package com.travessia.segura.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.travessia.segura.R
import com.travessia.segura.config.AppConfig
import com.travessia.segura.config.LanguageManager
import com.travessia.segura.voice.VoiceCommandController
import com.travessia.segura.voice.VoiceSystem

class LoginActivity : AppCompatActivity(), VoiceCommandController.Listener {

    companion object {
        private const val MICROPHONE_PERMISSION_CODE = 201
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvLanguageLabel: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvVoiceStatus: TextView
    private lateinit var btnPortuguese: Button
    private lateinit var btnEnglish: Button
    private lateinit var btnSpanish: Button
    private lateinit var btnContinue: Button
    private lateinit var btnVoiceLanguage: Button

    private lateinit var config: AppConfig
    private lateinit var voiceSystem: VoiceSystem
    private lateinit var voiceCommands: VoiceCommandController

    private var selectedLanguage: String = LanguageManager.PT
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_login)

        config = AppConfig(this)
        selectedLanguage = LanguageManager.currentLanguage(this)
        voiceSystem = VoiceSystem(this, config)
        voiceCommands = VoiceCommandController(
            context = this,
            languageProvider = { selectedLanguage },
            listener = this
        )

        bindViews()
        aplicarInsetsSistema()
        bindActions()
        applyTexts()
        updateSelectedButton()
    }

    private fun bindViews() {
        tvTitle = findViewById(R.id.tvLoginTitle)
        tvSubtitle = findViewById(R.id.tvLoginSubtitle)
        tvLanguageLabel = findViewById(R.id.tvLoginLanguageLabel)
        tvHint = findViewById(R.id.tvLoginHint)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        btnPortuguese = findViewById(R.id.btnPortuguese)
        btnEnglish = findViewById(R.id.btnEnglish)
        btnSpanish = findViewById(R.id.btnSpanish)
        btnContinue = findViewById(R.id.btnContinue)
        btnVoiceLanguage = findViewById(R.id.btnVoiceLanguage)
    }

    private fun bindActions() {
        btnPortuguese.setOnClickListener { selectLanguage(LanguageManager.PT) }
        btnEnglish.setOnClickListener { selectLanguage(LanguageManager.EN) }
        btnSpanish.setOnClickListener { selectLanguage(LanguageManager.ES) }
        btnContinue.setOnClickListener { openMain() }
        btnVoiceLanguage.setOnClickListener { startLanguageVoiceSelection() }
    }

    private fun aplicarInsetsSistema() {
        val root = findViewById<android.view.View>(R.id.rootLogin)
        val l = root.paddingLeft
        val t = root.paddingTop
        val r = root.paddingRight
        val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun selectLanguage(code: String) {
        selectedLanguage = code
        LanguageManager.setLanguage(this, selectedLanguage)
        config.appLanguage = selectedLanguage
        applyTexts()
        updateSelectedButton()
    }

    private fun openMain() {
        if (navigating) return
        navigating = true
        LanguageManager.setLanguage(this, selectedLanguage)
        config.appLanguage = selectedLanguage
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun applyTexts() {
        tvTitle.text = LanguageManager.text(selectedLanguage, "login_title")
        tvSubtitle.text = LanguageManager.text(selectedLanguage, "login_subtitle")
        tvLanguageLabel.text = LanguageManager.text(selectedLanguage, "login_language_label")
        tvHint.text = LanguageManager.text(selectedLanguage, "login_hint")
        btnContinue.text = LanguageManager.text(selectedLanguage, "login_continue")
        btnPortuguese.text = LanguageManager.text(selectedLanguage, "login_pt")
        btnEnglish.text = LanguageManager.text(selectedLanguage, "login_en")
        btnSpanish.text = LanguageManager.text(selectedLanguage, "login_es")
        btnVoiceLanguage.text = LanguageManager.text(selectedLanguage, "login_voice")

        btnPortuguese.contentDescription = LanguageManager.text(LanguageManager.PT, "login_pt")
        btnEnglish.contentDescription = LanguageManager.text(LanguageManager.EN, "login_en")
        btnSpanish.contentDescription = LanguageManager.text(LanguageManager.ES, "login_es")
        btnVoiceLanguage.contentDescription = LanguageManager.text(selectedLanguage, "login_voice")
        btnContinue.contentDescription = LanguageManager.text(selectedLanguage, "login_continue")
    }

    private fun updateSelectedButton() {
        btnPortuguese.setBackgroundResource(
            if (selectedLanguage == LanguageManager.PT) R.drawable.bg_button_primary else R.drawable.bg_button_secondary
        )
        btnEnglish.setBackgroundResource(
            if (selectedLanguage == LanguageManager.EN) R.drawable.bg_button_primary else R.drawable.bg_button_secondary
        )
        btnSpanish.setBackgroundResource(
            if (selectedLanguage == LanguageManager.ES) R.drawable.bg_button_primary else R.drawable.bg_button_secondary
        )
    }

    private fun startLanguageVoiceSelection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            tvVoiceStatus.text = LanguageManager.text(selectedLanguage, "login_voice_permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_PERMISSION_CODE
            )
            return
        }

        voiceSystem.interromper()
        if (!voiceCommands.startListening()) {
            val msg = LanguageManager.text(selectedLanguage, "login_voice_unavailable")
            tvVoiceStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onListeningChanged(isListening: Boolean) {
        runOnUiThread {
            btnVoiceLanguage.isEnabled = !isListening
            btnVoiceLanguage.text = if (isListening) {
                LanguageManager.text(selectedLanguage, "voice_button_listening")
            } else {
                LanguageManager.text(selectedLanguage, "login_voice")
            }
            tvVoiceStatus.text = if (isListening) {
                LanguageManager.text(selectedLanguage, "login_voice_listening")
            } else {
                ""
            }
        }
    }

    override fun onCommand(command: VoiceCommandController.Command, rawText: String) {
        runOnUiThread {
            when (command) {
                VoiceCommandController.Command.LANGUAGE_PT -> selectLanguageAndContinue(LanguageManager.PT, "login_voice_selected_pt")
                VoiceCommandController.Command.LANGUAGE_EN -> selectLanguageAndContinue(LanguageManager.EN, "login_voice_selected_en")
                VoiceCommandController.Command.LANGUAGE_ES -> selectLanguageAndContinue(LanguageManager.ES, "login_voice_selected_es")
                VoiceCommandController.Command.HELP -> {
                    val msg = LanguageManager.text(selectedLanguage, "login_voice_help")
                    tvVoiceStatus.text = msg
                    voiceSystem.forcar("voice_help", msg)
                }
                else -> {
                    val msg = LanguageManager.text(selectedLanguage, "voice_unknown")
                    tvVoiceStatus.text = msg
                    voiceSystem.forcar("voice_unknown", msg)
                }
            }
        }
    }

    private fun selectLanguageAndContinue(code: String, confirmationKey: String) {
        selectLanguage(code)
        // Se a pessoa escolheu o idioma por voz, mantemos o fluxo acessível:
        // a tela principal abre e escuta automaticamente um comando (ex.: "iniciar").
        config.voiceCommandsEnabled = true
        config.voiceAutoListen = true
        val msg = LanguageManager.text(code, confirmationKey)
        tvVoiceStatus.text = msg
        voiceSystem.forcar("voice_language", msg)
        Handler(Looper.getMainLooper()).postDelayed({ openMain() }, 1200L)
    }

    override fun onRecognitionError(errorCode: Int) {
        runOnUiThread {
            val quietErrors = setOf(
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT
            )
            val msg = if (errorCode in quietErrors) {
                LanguageManager.text(selectedLanguage, "voice_unknown")
            } else {
                LanguageManager.text(selectedLanguage, "login_voice_unavailable")
            }
            tvVoiceStatus.text = msg
            voiceSystem.forcar("voice_error", msg)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLanguageVoiceSelection()
            } else {
                val msg = LanguageManager.text(selectedLanguage, "login_voice_permission")
                tvVoiceStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        voiceCommands.destroy()
        voiceSystem.destruir()
        super.onDestroy()
    }
}
