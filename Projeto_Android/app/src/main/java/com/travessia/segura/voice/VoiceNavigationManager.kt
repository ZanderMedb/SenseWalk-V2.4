package com.travessia.segura.voice

import com.travessia.segura.config.AppConfig

/**
 * Camada de prioridade para GPS/contexto. Avisos críticos da visão usam SAFETY e
 * interrompem instruções comuns; a navegação nunca deve ocultar um alerta de trânsito.
 */
class VoiceNavigationManager(
    private val config: AppConfig,
    private val voice: VoiceSystem
) {
    fun navigation(key: String, message: String) {
        if (config.voiceNavigationEnabled) voice.falarPrioritario(key, message, VoicePriority.NAVIGATION)
    }

    fun context(key: String, message: String) {
        if (config.voiceNavigationEnabled) voice.falarPrioritario(key, message, VoicePriority.CROSSING)
    }

    fun safety(key: String, message: String) {
        if (config.voiceNavigationEnabled) voice.forcarPrioritario(key, message, VoicePriority.SAFETY)
    }
}
