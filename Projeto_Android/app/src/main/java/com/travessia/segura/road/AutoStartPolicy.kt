package com.travessia.segura.road

/**
 * Quando a FFNet pode iniciar sozinha uma análise. Depois de "Parar", "Terminei" ou "Finalizar travessia"
 * ([userStopped]) a FFNet NUNCA reinicia: só "Iniciar análise" / "Nova travessia" ou o botão.
 */
object AutoStartPolicy {
    fun shouldAutoStart(foreground: Boolean, userStopped: Boolean, analysisActive: Boolean, monitoring: Boolean,
        tokenCurrent: Boolean, ready: Boolean): Boolean =
        foreground && !userStopped && !analysisActive && monitoring && tokenCurrent && ready
}
