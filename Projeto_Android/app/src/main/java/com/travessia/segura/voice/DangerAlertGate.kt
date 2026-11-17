package com.travessia.segura.voice

/**
 * Um alerta de perigo por veículo: o mesmo veículo (trackId) entrando e saindo do perigo não gera
 * um novo "Atenção!" a cada oscilação. Veículo NOVO alerta imediatamente. Perigo persistente do mesmo
 * veículo volta a ser falado só depois de [repeatAfterMs]. Perigo sem veículo identificado (ex.: rastreio
 * perdido) segue o mesmo intervalo.
 */
class DangerAlertGate(private val repeatAfterMs: Long = 15000) {
    private val alerted = mutableSetOf<Int>()
    private var lastAlertAt = Long.MIN_VALUE

    @Synchronized fun shouldAlert(trackIds: Set<Int>, nowMs: Long): Boolean {
        val fresh = trackIds.any { it !in alerted }
        val repeat = lastAlertAt == Long.MIN_VALUE || nowMs - lastAlertAt >= repeatAfterMs
        if (!fresh && !repeat) return false
        alerted += trackIds
        lastAlertAt = nowMs
        return true
    }

    @Synchronized fun reset() { alerted.clear(); lastAlertAt = Long.MIN_VALUE }
}
