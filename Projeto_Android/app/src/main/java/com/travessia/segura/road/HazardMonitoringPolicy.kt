package com.travessia.segura.road

/** Consentimento controla a sequência, nunca a disponibilidade dos alertas de trânsito. */
object HazardMonitoringPolicy {
    fun enabled(manual: Boolean, monitoring: Boolean) = manual || monitoring
}
