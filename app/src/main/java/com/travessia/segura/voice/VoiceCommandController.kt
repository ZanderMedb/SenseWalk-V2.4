package com.travessia.segura.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.travessia.segura.config.LanguageManager
import java.text.Normalizer
import java.util.Locale

/**
 * Reconhecimento de comandos curtos e fechados para acessibilidade.
 *
 * O SpeechRecognizer do Android e usado apenas para converter a fala em texto.
 * A decisao do comando e local e deterministica: nao ha LLM nem interpretacao aberta.
 */
class VoiceCommandController(
    context: Context,
    private val languageProvider: () -> String,
    private val listener: Listener
) : RecognitionListener {

    enum class Command {
        START,
        STOP,
        STATUS,
        SOURCE_PHONE,
        SOURCE_EXTERNAL,
        SETTINGS,
        HELP,
        LANGUAGE_PT,
        LANGUAGE_EN,
        LANGUAGE_ES,
        UNKNOWN
    }

    interface Listener {
        fun onListeningChanged(isListening: Boolean)
        fun onCommand(command: Command, rawText: String)
        fun onRecognitionError(errorCode: Int)
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening(): Boolean {
        if (!isAvailable()) return false
        if (listening) return true

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(this)
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag(languageProvider()))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag(languageProvider()))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "SenseWalk")
        }

        return try {
            listening = true
            listener.onListeningChanged(true)
            recognizer?.startListening(intent)
            true
        } catch (_: Exception) {
            listening = false
            listener.onListeningChanged(false)
            false
        }
    }

    fun stopListening() {
        if (!listening) return
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        listening = false
        listener.onListeningChanged(false)
    }

    fun cancel() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        if (listening) {
            listening = false
            listener.onListeningChanged(false)
        }
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        listening = false
        listener.onListeningChanged(false)
        listener.onRecognitionError(error)
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onResults(results: Bundle?) {
        listening = false
        listener.onListeningChanged(false)

        val candidates = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()

        if (candidates.isEmpty()) {
            listener.onCommand(Command.UNKNOWN, "")
            return
        }

        for (candidate in candidates) {
            val command = parse(candidate)
            if (command != Command.UNKNOWN) {
                listener.onCommand(command, candidate)
                return
            }
        }

        listener.onCommand(Command.UNKNOWN, candidates.first())
    }

    private fun parse(raw: String): Command {
        val text = normalize(raw)

        // Idiomas - usados principalmente na tela inicial.
        if (containsAny(text, "portugues", "portuguese", "brasil", "brasileiro")) {
            return Command.LANGUAGE_PT
        }
        if (containsAny(text, "english", "ingles", "inglese")) {
            return Command.LANGUAGE_EN
        }
        if (containsAny(text, "espanol", "espanhol", "spanish", "castellano")) {
            return Command.LANGUAGE_ES
        }

        // STOP vem antes de START para priorizar a interrupcao.
        if (containsAny(text, "parar", "pare", "parar analise", "stop", "stop analysis", "detener", "deten", "parar analisis")) {
            return Command.STOP
        }
        if (containsAny(text, "iniciar", "iniciar analise", "comecar", "comece", "start", "start analysis", "begin", "iniciar analisis", "comenzar")) {
            return Command.START
        }
        if (containsAny(text, "status", "estado", "situacao", "situation", "state", "estado actual", "situacion")) {
            return Command.STATUS
        }
        if (containsAny(text, "camera do celular", "celular", "telefone", "phone camera", "phone", "mobile camera", "camara del celular", "telefono")) {
            return Command.SOURCE_PHONE
        }
        if (containsAny(text, "camera externa", "esp32", "esp 32", "oculos", "external camera", "glasses camera", "camara externa", "gafas")) {
            return Command.SOURCE_EXTERNAL
        }
        if (containsAny(text, "configuracoes", "configuracao", "ajustes", "settings", "configuration", "configuracion")) {
            return Command.SETTINGS
        }
        if (containsAny(text, "ajuda", "comandos", "help", "commands", "ayuda", "comandos de voz")) {
            return Command.HELP
        }

        return Command.UNKNOWN
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return decomposed
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun containsAny(text: String, vararg terms: String): Boolean {
        return terms.any { normalize(it) in text }
    }

    private fun localeTag(code: String): String {
        return when (code) {
            LanguageManager.EN -> "en-US"
            LanguageManager.ES -> "es-ES"
            else -> "pt-BR"
        }
    }
}
