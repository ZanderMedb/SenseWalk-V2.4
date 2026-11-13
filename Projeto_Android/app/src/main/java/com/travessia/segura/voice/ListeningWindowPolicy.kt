package com.travessia.segura.voice

/**
 * Janelas de escuta controladas (o SpeechRecognizer do Android não oferece escuta contínua estável).
 *  - Nunca abre o microfone enquanto o TTS fala (evita reconhecer a própria fala do app).
 *  - Reabre após cada resultado/erro "normal" (silêncio, nada reconhecido) com pequeno atraso.
 *  - Erros reais (serviço ocupado, rede, permissão) usam recuo exponencial; após [maxHardErrors]
 *    consecutivos a limitação é comunicada uma vez e as novas tentativas passam a ocorrer só a cada
 *    [degradedRetryMs]. Isso impede ciclos rápidos de reinicialização sem abandonar a voz.
 */
class ListeningWindowPolicy(
    private val reopenDelayMs: Long = 600,
    private val afterSpeechDelayMs: Long = 450,
    private val baseBackoffMs: Long = 1000,
    private val maxBackoffMs: Long = 8000,
    private val maxHardErrors: Int = 5,
    private val degradedRetryMs: Long = 15000
) {
    var hardErrors = 0; private set
    var disabled = false; private set
    private var notBefore = 0L
    private var limitationAnnounced = false

    fun shouldOpen(wanted: Boolean, ttsBusy: Boolean, listening: Boolean, now: Long): Boolean =
        wanted && !ttsBusy && !listening && now >= notBefore

    /** TTS terminou: dar um pequeno intervalo antes de abrir o microfone (eco/cauda do áudio). */
    fun onSpeechFinished(now: Long) { notBefore = maxOf(notBefore, now + afterSpeechDelayMs) }

    fun onResult(now: Long) { hardErrors = 0; disabled = false; notBefore = now + reopenDelayMs }

    /** @return true se a limitação deve ser anunciada agora (somente uma vez). */
    fun onError(quiet: Boolean, now: Long): Boolean {
        // Silêncio/nada reconhecido: o serviço funciona; reabrir sem contar como falha.
        if (quiet) { hardErrors = 0; disabled = false; notBefore = now + reopenDelayMs; return false }
        hardErrors++
        val backoff = (baseBackoffMs shl (hardErrors - 1).coerceAtMost(4)).coerceAtMost(maxBackoffMs)
        notBefore = now + backoff
        if (hardErrors >= maxHardErrors) {
            // "disabled" = modo degradado: tenta de novo raramente, nunca em ciclo rápido.
            disabled = true
            notBefore = now + degradedRetryMs
            if (!limitationAnnounced) { limitationAnnounced = true; return true }
        }
        return false
    }

    /** Nova sessão/comando explícito do usuário: permite tentar novamente. */
    fun reset() { hardErrors = 0; disabled = false; notBefore = 0L; limitationAnnounced = false }
}
