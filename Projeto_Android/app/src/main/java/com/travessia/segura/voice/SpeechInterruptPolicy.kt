package com.travessia.segura.voice

/**
 * Quem pode cortar a fala em andamento.
 *  - Perigo (SAFETY) e Parar/Cancelar (CONTROL) interrompem qualquer fala de prioridade menor.
 *  - Instruções (CROSSING) e informativas (INFO) NUNCA cortam outra fala: esperam na fila (curta).
 *  - Mudança de estado do mesmo tópico: instruções de câmera, "aguarde" e alertas TERMINAM a frase (não
 *    ficam cortadas pela metade); conclusões e "sinal verde" superados SÃO cortados, porque continuar
 *    falando uma informação favorável que deixou de valer é perigoso. Na fila, o superado é descartado.
 */
object SpeechInterruptPolicy {
    fun interrupts(current: VoicePriority?, incoming: VoicePriority): Boolean =
        current != null && incoming.weight >= VoicePriority.CONTROL.weight && current.weight < incoming.weight

    private val FINISH_SENTENCE = setOf("AWAIT_RIGHT", "AWAIT_LEFT", "AWAIT_FRONT", "SIGNAL_RED", "CROSSWALK_WAIT", "DANGER")

    fun cutOnStateChange(spokenState: String?): Boolean = spokenState !in FINISH_SENTENCE
}
