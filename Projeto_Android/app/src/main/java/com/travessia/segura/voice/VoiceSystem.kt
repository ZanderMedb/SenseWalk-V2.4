package com.travessia.segura.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.travessia.segura.config.AppConfig
import com.travessia.segura.config.LanguageManager
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observações faladas por transição de estado; comandos explícitos mantêm fila/cooldowns.
 * A linguagem do TTS acompanha o idioma escolhido em Configurações/Login.
 */
class VoiceSystem(
    context: Context,
    private val config: AppConfig
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceSystem"
        private const val MAX_QUEUE = 3
    }

    private var tts: TextToSpeech? = null
    private val fila = VoiceMessageScheduler(MAX_QUEUE)
    private val observedStates = SpeechStateGate()
    private val ultimos = mutableMapOf<String, Double>()
    private val falando = AtomicBoolean(false)
    private var idiomaAtualTts: String = config.appLanguage
    @Volatile private var currentUtteranceId: String? = null
    @Volatile private var currentPriority: VoicePriority? = null
    private var currentMessage: ScheduledVoiceMessage? = null
    @Volatile private var speakRequestedAtMs = 0L
    @Volatile private var decisionAtMs = 0L
    private var question: Pair<String, (Boolean) -> Unit>? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    var funcionando = false
        private set

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            aplicarIdiomaTts(forcar = true)
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.1f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?)  {
                    if (id == currentUtteranceId) {
                        falando.set(true)
                        Log.d(TAG, "tts_start_delay_ms=${android.os.SystemClock.elapsedRealtime()-speakRequestedAtMs} priority=$currentPriority")
                        if (decisionAtMs > 0L) Log.d(TAG,
                            "decision_to_tts_start_ms=${android.os.SystemClock.elapsedRealtime()-decisionAtMs} priority=$currentPriority")
                    }
                }
                override fun onDone(id: String?)   { finishUtterance(id, true) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?)  { finishUtterance(id) }
                override fun onStop(id: String?, interrupted: Boolean) { finishUtterance(id) }
            })

            funcionando = true
            Log.i(TAG, "TTS pronto!")
        } else {
            funcionando = false
            Log.e(TAG, "Falha ao inicializar TTS: status=$status")
        }
    }

    private fun localeParaIdioma(code: String): Locale {
        return when (code) {
            LanguageManager.EN -> Locale("en", "US")
            LanguageManager.ES -> Locale("es", "ES")
            else -> Locale("pt", "BR")
        }
    }

    private fun aplicarIdiomaTts(forcar: Boolean = false) {
        val novoIdioma = config.appLanguage
        if (!forcar && novoIdioma == idiomaAtualTts) return

        idiomaAtualTts = novoIdioma
        val localePrincipal = localeParaIdioma(novoIdioma)
        val result = tts?.setLanguage(localePrincipal)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallback = when (novoIdioma) {
                LanguageManager.EN -> Locale("en")
                LanguageManager.ES -> Locale("es")
                else -> Locale("pt")
            }
            tts?.setLanguage(fallback)
            Log.w(TAG, "Idioma TTS $novoIdioma indisponivel, usando fallback ${fallback.language}")
        }
    }

    /** Fala com cooldown. Se dentro do cooldown, ignora silenciosamente. */
    fun falar(chave: String, texto: String) {
        falarPrioritario(chave, texto, VoicePriority.CROSSING)
    }

    @Synchronized fun observarEstado(topic: String, state: String, settleMs: Long = 0): Boolean {
        val previous = observedStates.current(topic)
        val stable = observedStates.observe(topic,state,android.os.SystemClock.elapsedRealtime(),settleMs)
        if (observedStates.current(topic) != previous) {
            fila.remove("state:$topic")
            if (currentMessage?.stateTopic == topic) {
                stopCurrent()
                processarFila()
            }
        }
        return stable
    }

    /** Sem cooldown periódico: uma fala por valor observado, rearmada somente pela transição. */
    @Synchronized fun falarEstado(topic: String, state: String, text: String,
        priority: VoicePriority = VoicePriority.CROSSING, settleMs: Long = 0) {
        if (!observarEstado(topic,state,settleMs) || !funcionando || !observedStates.needsSpeech(topic,state)) return
        val key = "state:$topic"
        if (fila.contains(key) || currentMessage?.let { it.stateTopic == topic && it.stateValue == state } == true) return
        aplicarIdiomaTts()
        if (fila.offer(ScheduledVoiceMessage(key,text,priority,android.os.SystemClock.elapsedRealtime(),topic,state,
                android.os.SystemClock.elapsedRealtime()))) {
            if (priority == VoicePriority.SAFETY) fila.dropBelow(priority)
            if (priority == VoicePriority.SAFETY && (currentPriority?.weight ?: Int.MAX_VALUE) < priority.weight) {
                stopCurrent()
            }
            processarFila()
        }
    }

    @Synchronized fun resetarEstados(prefix: String) {
        observedStates.reset(prefix); fila.removePrefix("state:$prefix")
        if (currentMessage?.stateTopic?.startsWith(prefix) == true) { stopCurrent(); processarFila() }
    }

    /** Insere uma mensagem sem interromper uma de prioridade superior já em andamento. */
    @Synchronized fun falarPrioritario(chave: String, texto: String, prioridade: VoicePriority) {
        if (!funcionando) return
        aplicarIdiomaTts()

        val agora = now()
        val cd = 3.0
        if (ultimos[chave]?.let { agora - it < cd } == true) return

        if (fila.offer(ScheduledVoiceMessage(chave, texto, prioridade, android.os.SystemClock.elapsedRealtime(),
                decisionElapsedMs = android.os.SystemClock.elapsedRealtime()))) {
            if (prioridade == VoicePriority.SAFETY) fila.dropBelow(prioridade)
            ultimos[chave] = agora
            if (prioridade == VoicePriority.SAFETY && (currentPriority?.weight ?: Int.MAX_VALUE) < prioridade.weight) {
                stopCurrent()
            }
            processarFila()
        }
        Log.d(TAG, "[VOZ] [$chave] $texto")
    }

    /** Comando explícito, ainda subordinado aos alertas de perigo. */
    fun forcar(chave: String, texto: String) {
        forcarPrioritario(chave, texto, VoicePriority.CROSSING)
    }

    /** Alertas críticos (ex.: semáforo vermelho) interrompem instruções de menor prioridade. */
    @Synchronized fun forcarPrioritario(chave: String, texto: String, prioridade: VoicePriority) {
        if (!funcionando) { Log.d(TAG, "[VOZ!] OFF $texto"); return }
        if ((currentPriority?.weight ?: 0) > prioridade.weight || fila.hasHigherPriorityThan(prioridade)) {
            falarPrioritario(chave,texto,prioridade)
            return
        }
        aplicarIdiomaTts()
        limpar()
        fila.offer(ScheduledVoiceMessage(chave, texto, prioridade, android.os.SystemClock.elapsedRealtime(),
            decisionElapsedMs = android.os.SystemClock.elapsedRealtime()))
        val agora = now()
        ultimos[chave] = agora
        tts?.stop()
        falando.set(false)
        processarFila()
        Log.d(TAG, "[VOZ!] [$chave] $texto")
    }

    @Synchronized private fun processarFila() {
        if (falando.get()) return
        var mensagem = fila.poll() ?: return
        while (android.os.SystemClock.elapsedRealtime() - mensagem.timestampMs !in 0..3000 ||
            (mensagem.stateTopic != null && observedStates.current(mensagem.stateTopic!!) != mensagem.stateValue)) {
            mensagem = fila.poll() ?: return
        }
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, config.volumeVoz)
        }
        // onStart é assíncrono; marque antes de speak para outra origem não limpar esta fala.
        falando.set(true)
        val id = "u_${System.nanoTime()}"
        currentUtteranceId = id; currentPriority = mensagem.priority
        currentMessage = mensagem
        decisionAtMs = mensagem.decisionElapsedMs
        speakRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        if (tts?.speak(mensagem.text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finishUtterance(id)
    }

    @Synchronized private fun limpar() {
        fila.clear()
        stopCurrent()
    }

    private fun stopCurrent() {
        completeQuestion(false)
        currentUtteranceId = null; currentPriority = null
        currentMessage = null
        tts?.stop()
        falando.set(false)
    }

    @Synchronized private fun finishUtterance(id: String?, success: Boolean = false) {
        if (id == null || id != currentUtteranceId) return
        val message = currentMessage
        if (success && message?.stateTopic != null && message.stateValue != null)
            observedStates.markSpoken(message.stateTopic,message.stateValue)
        if (question?.first == id) completeQuestion(success)
        currentUtteranceId = null; currentPriority = null
        currentMessage = null
        falando.set(false); processarFila()
    }

    private fun completeQuestion(success: Boolean) {
        val callback = question?.second ?: return
        question = null
        mainHandler.post { callback(success) }
    }

    /** Microfone só deve abrir após onDone real do TTS, nunca por atraso estimado. */
    @Synchronized fun ask(text: String, finished: (Boolean) -> Unit) {
        if (!funcionando || config.volumeVoz <= 0f) { finished(false); return }
        if (currentPriority == VoicePriority.SAFETY || fila.hasHigherPriorityThan(VoicePriority.CROSSING)) {
            finished(false)
            return
        }
        limpar(); aplicarIdiomaTts()
        val id = "question_${System.nanoTime()}"
        question = id to finished
        currentUtteranceId = id; currentPriority = VoicePriority.CROSSING
        decisionAtMs = android.os.SystemClock.elapsedRealtime()
        speakRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        falando.set(true)
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, config.volumeVoz) }
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finishUtterance(id)
    }

    /** Cancelar consentimento não cancela um alerta que tenha interrompido a pergunta. */
    @Synchronized fun cancelQuestion() {
        if (question != null) { stopCurrent(); processarFila() }
    }

    /** Comandos não cortam perigo; parada/ciclo de vida podem encerrar toda a sessão. */
    @Synchronized fun interromper(preserveSafety: Boolean = true) {
        if (!preserveSafety) { limpar(); return }
        fila.dropBelow(VoicePriority.SAFETY)
        if (currentPriority != VoicePriority.SAFETY) { stopCurrent(); processarFila() }
    }

    fun destruir() {
        limpar()
        tts?.stop()
        tts?.shutdown()
    }

    private fun now(): Double = android.os.SystemClock.elapsedRealtime() / 1000.0
}
