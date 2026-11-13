package com.travessia.segura.tracking

/**
 * Classes de movimento no plano da IMAGEM. Nenhuma afirma distância, velocidade física ou TTC.
 *
 * PARADO               sem movimento independente aparente, com câmera estimada e histórico >= 1 s.
 * EM_MOVIMENTO         deslocamento lateral em relação ao fundo.
 * POTENCIAL_CONFLITO   deslocamento lateral rumo ao centro da imagem (possível conflito).
 * POSSIVEL_APROXIMACAO expansão da caixa com câmera parada (corrigida pela escala do fundo).
 * POSSIVEL_AFASTAMENTO retração da caixa com câmera parada. NUNCA é prova de afastamento seguro.
 * MOVIMENTO_INCERTO    movimento independente não identificado (câmera girando, andando, paralaxe,
 *                      histórico curto). Ausência de classificação não é ausência de perigo.
 * APROXIMANDO          legado; não é produzido pelo rastreador atual.
 */
object MotionClass {
    const val PARADO = "PARADO"
    /** Estado ESTABILIZADO (memória da sessão): 3 observações compatíveis com ausência de movimento próprio. */
    const val APARENTEMENTE_PARADO = "APARENTEMENTE_PARADO"
    fun isStopped(c: String) = c == PARADO || c == APARENTEMENTE_PARADO
    const val EM_MOVIMENTO = "EM_MOVIMENTO"
    const val POTENCIAL_CONFLITO = "POTENCIAL_CONFLITO"
    const val POSSIVEL_APROXIMACAO = "POSSIVEL_APROXIMACAO"
    const val POSSIVEL_AFASTAMENTO = "POSSIVEL_AFASTAMENTO"
    const val MOVIMENTO_INCERTO = "MOVIMENTO_INCERTO"
    const val APROXIMANDO = "APROXIMANDO"

    /** Classes que impedem "via livre": qualquer movimento aparente ou possível. */
    val MOVING = setOf(APROXIMANDO, POTENCIAL_CONFLITO, EM_MOVIMENTO, POSSIVEL_APROXIMACAO, POSSIVEL_AFASTAMENTO)
    /** Classes que geram alerta de possível conflito. */
    val CONFLICT = setOf(POTENCIAL_CONFLITO, POSSIVEL_APROXIMACAO)
    /** Classes que tornam a avaliação inconclusiva (motionReliable = false). */
    val UNCERTAIN = setOf(MOVIMENTO_INCERTO, EM_MOVIMENTO, POTENCIAL_CONFLITO, POSSIVEL_APROXIMACAO, POSSIVEL_AFASTAMENTO)
}

/** Classificação com nível de confiança (0..1) e motivo, para diagnóstico e logs. */
data class MotionAssessment(val classification: String, val confidence: Float, val speedPxS: Float,
    val growthPerSecond: Float, val reason: String)
