package com.travessia.segura.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.travessia.segura.config.AppConfig
import com.travessia.segura.config.LanguageManager
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sistema de voz com fila e cooldowns por chave.
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
    private val fila    = ConcurrentLinkedQueue<String>()
    private val ultimos = mutableMapOf<String, Double>()
    private val falando = AtomicBoolean(false)
    private var tUltimaFala = now()
    private var idiomaAtualTts: String = config.appLanguage

    var funcionando = false
        private set

    private val cooldowns = mapOf(
        "inicio"           to 0.0,
        "cancelado"        to 0.0,
        "recon_resultado"  to 0.0,
        "recon_progresso"  to 4.0,

        "semaforo_cor"     to 0.5,
        "semaforo_pode"    to 5.0,
        "semaforo_aguarde" to 7.0,
        "semaforo_veiculo" to 5.0,
        "semaforo_perdido" to 8.0,

        "faixa_alinhamento" to 3.5,
        "faixa_livre"      to 5.0,
        "faixa_parados"    to 5.0,
        "faixa_parando"    to 4.0,
        "faixa_movimento"  to 5.0,

        "via_livre"        to 5.0,
        "via_parados"      to 5.0,
        "via_parando"      to 4.0,
        "via_movimento"    to 5.0,

        "upgrade"          to 0.0,
        "heartbeat"        to 4.0
    )

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            aplicarIdiomaTts(forcar = true)
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.1f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?)  { falando.set(true) }
                override fun onDone(id: String?)   { falando.set(false); processarFila() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?)  { falando.set(false); processarFila() }
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
        if (!funcionando) return
        aplicarIdiomaTts()

        val agora = now()
        val cd = cooldowns[chave] ?: 3.0
        if ((agora - (ultimos[chave] ?: 0.0)) < cd) return

        if (fila.size < MAX_QUEUE) {
            fila.add(texto)
            ultimos[chave] = agora
            tUltimaFala = agora
            processarFila()
        }
        Log.d(TAG, "[VOZ] [$chave] $texto")
    }

    /** Força fala imediata — limpa tudo antes. */
    fun forcar(chave: String, texto: String) {
        if (!funcionando) { Log.d(TAG, "[VOZ!] OFF $texto"); return }
        aplicarIdiomaTts()
        limpar()
        fila.add(texto)
        val agora = now()
        ultimos[chave] = agora
        tUltimaFala = agora
        tts?.stop()
        falando.set(false)
        processarFila()
        Log.d(TAG, "[VOZ!] [$chave] $texto")
    }

    private fun processarFila() {
        if (falando.get()) return
        val texto = fila.poll() ?: return
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, config.volumeVoz)
        }
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, params, "u_${System.currentTimeMillis()}")
    }

    private fun limpar() {
        fila.clear()
        tts?.stop()
        falando.set(false)
    }

    fun tempoSilencio(): Double = now() - tUltimaFala

    /** Interrompe a fala atual e limpa mensagens pendentes antes de ouvir um comando. */
    fun interromper() {
        limpar()
    }

    fun destruir() {
        tts?.stop()
        tts?.shutdown()
    }

    private fun now(): Double = System.currentTimeMillis() / 1000.0
}
