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

    companion object { private const val TAG = "VoiceCmd" }

    enum class Command {
        START,
        STOP,
        /** "Terminei" / "Finalizar travessia": encerra a sessão (fica parado; a FFNet não reinicia). */
        FINISH,
        STATUS,
        SOURCE_PHONE,
        SOURCE_EXTERNAL,
        SOURCE_USB,
        /** "Pronto": usuário terminou de apontar a câmera. Declaração, não prova de cobertura. */
        CONFIRM_DIRECTION,
        CONTINUE,
        REPEAT,
        CANCEL,
        RESTART,
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
    val isListening: Boolean get() = listening
    /** Comando já entregue por resultado parcial nesta janela: o final é ignorado (sem execução dupla). */
    private var deliveredThisWindow = false
    /** Após erros de cliente/ocupado, recriar o reconhecedor evita ficar preso em ERROR_RECOGNIZER_BUSY. */
    private var recreateRecognizer = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening(): Boolean {
        if (!isAvailable()) { android.util.Log.w(TAG, "SpeechRecognizer indisponível"); return false }
        if (listening) return true

        if (recreateRecognizer) {
            recreateRecognizer = false
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
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
            // Parciais: "pronto"/"parar" respondem antes do fim da janela.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "SenseWalk")
        }

        return try {
            listening = true
            deliveredThisWindow = false
            listener.onListeningChanged(true)
            recognizer?.startListening(intent)
            android.util.Log.i(TAG, "listen_open lang=${localeTag(languageProvider())}")
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "listen_open_failed ${e.message}")
            recreateRecognizer = true
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
        android.util.Log.i(TAG, "listen_error code=$error delivered=$deliveredThisWindow")
        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == 11 /* ERROR_SERVER_DISCONNECTED */) recreateRecognizer = true
        val wasListening = listening
        listening = false
        if (deliveredThisWindow) { if (wasListening) listener.onListeningChanged(false); return }
        listener.onListeningChanged(false)
        listener.onRecognitionError(error)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (deliveredThisWindow || !listening) return
        val candidates = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (candidate in candidates) {
            val command = parse(candidate)
            // Parcial só para comandos inequívocos; o texto final decide os demais.
            if (command != Command.UNKNOWN) {
                android.util.Log.i(TAG, "listen_partial '$candidate' -> $command")
                deliveredThisWindow = true
                listening = false
                runCatching { recognizer?.cancel() }
                listener.onListeningChanged(false)
                listener.onCommand(command, candidate)
                return
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val wasListening = listening
        listening = false
        if (deliveredThisWindow) { if (wasListening) listener.onListeningChanged(false); return }
        deliveredThisWindow = true
        listener.onListeningChanged(false)

        val candidates = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        android.util.Log.i(TAG, "listen_result $candidates")

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

        // Stop takes precedence even when a language/source is mentioned in the same utterance.
        if (containsAny(text, "parar", "pare", "stop", "detener", "deten")) return Command.STOP
        // Negated requests must not start analysis from a substring match.
        if (containsAny(text,"nao", "not", "no", "dont", "don t")) return Command.UNKNOWN

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
        if (containsAny(text, "terminei", "terminar travessia", "finalizar travessia", "finalizar", "encerrar travessia",
                "finished", "i m done", "finish crossing", "end crossing", "termine", "terminar cruce", "finalizar cruce")) {
            return Command.FINISH
        }
        if (containsAny(text, "analisar novamente", "analisar de novo", "verificar novamente", "verificar de novo",
                "analyze again", "check again", "analizar de nuevo", "analizar otra vez", "verificar otra vez")) {
            return Command.RESTART
        }
        if (containsAny(text, "cancelar", "cancela", "cancel")) return Command.CANCEL
        if (containsAny(text, "repetir", "repita", "repete", "repeat", "say again", "repite")) return Command.REPEAT
        if (containsAny(text, "continuar", "continue", "continua", "proximo", "proxima", "next", "siguiente", "avancar")) {
            return Command.CONTINUE
        }
        if (containsAny(text, "iniciar", "iniciar analise", "nova travessia", "new crossing", "nuevo cruce", "nueva travesia", "comecar", "comece", "start", "start analysis", "begin", "iniciar analisis", "comenzar")) {
            return Command.START
        }
        if (containsAny(text, "status", "estado", "situacao", "situation", "state", "estado actual", "situacion")) {
            return Command.STATUS
        }
        if (containsAny(text,"pronto", "pronta", "ready", "done", "listo", "lista",
                "camera posicionada", "camera ready", "camara lista")) return Command.CONFIRM_DIRECTION
        if (containsAny(text,"usb", "uvc", "webcam")) return Command.SOURCE_USB
        if (containsAny(text, "camera do celular", "celular", "telefone", "phone camera", "phone", "mobile camera", "camara del celular", "telefono")) {
            return Command.SOURCE_PHONE
        }
        if (containsAny(text, "camera externa", "external camera", "camara externa")) {
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
        return terms.any { " ${normalize(it)} " in " $text " }
    }

    private fun localeTag(code: String): String {
        return when (code) {
            LanguageManager.EN -> "en-US"
            LanguageManager.ES -> "es-ES"
            else -> "pt-BR"
        }
    }
}
