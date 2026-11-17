package com.travessia.segura.logic

import android.util.Log
import com.travessia.segura.config.AppConfig
import com.travessia.segura.config.AppConfig.Companion.EST_FAIXA
import com.travessia.segura.config.AppConfig.Companion.EST_INATIVO
import com.travessia.segura.config.AppConfig.Companion.EST_RECONHECENDO
import com.travessia.segura.config.AppConfig.Companion.EST_SEMAFORO
import com.travessia.segura.config.AppConfig.Companion.EST_VIA_LIVRE
import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.tracking.Rastreador
import com.travessia.segura.voice.VoiceSystem
import com.travessia.segura.voice.VoicePriority
import com.travessia.segura.road.CrossingRiskAssessment
import com.travessia.segura.road.RiskReason
import com.travessia.segura.road.RoadText

/**
 * Interpreta observações visuais; nenhuma combinação autoriza uma travessia.
 * Ausência de veículos e semáforo verde continuam sendo condições não confirmadas.
 */
class StateMachine(
    private val config: AppConfig,
    private val voz: VoiceSystem,
    private val tracker: Rastreador
) {
    companion object {
        /** Desligado enquanto a detecção de faixa não é confiável (somente fala; detecção preservada). */
        const val CROSSWALK_ROUTINE_SPEECH = false
        private const val TAG = "StateMachine"
        // Confirma persistência da observação; não comprova segurança da via.
        private const val TEMPO_CONFIRMAR_LIVRE_S = 3.0
    }

    var estado = EST_INATIVO
        private set
    var situacaoAtual = "---"
        private set
    private var tEstado = now()

    // Reconhecimento
    private var reconFrames = 0
    private var reconSemaforo = 0
    private var reconFaixa = 0

    // Semáforo
    var semCorAtual = "NENHUM"
        private set
    private var tSemPerdido: Double? = null

    // Timer de via limpa / carros parados
    private var tViaLimpa: Double? = null
    private var tipoConfirmacao: String? = null
    private var aproximacaoAtiva = false
    private var currentRisk: CrossingRiskAssessment? = null

    // Upgrade counters
    private var upgradeSemCount = 0
    private var upgradeFaixaCount = 0

    // Faixa
    var faixaAlinhada = false
        private set
    var faixaCentroX = -1f
        private set

    fun tempoNoEstado(): Double = now() - tEstado

    // ================================================================
    //  TRANSIÇÃO DE ESTADO
    // ================================================================

    fun entrarEstado(novo: String) {
        if (novo == EST_INATIVO || novo == EST_RECONHECENDO) {
            voz.resetarEstados("scene_")
            aproximacaoAtiva = false
        }
        if (novo != EST_SEMAFORO) voz.observarEstado("scene_light","not_observed")
        if (novo != EST_FAIXA) voz.observarEstado("scene_alignment","not_applicable")
        Log.i(TAG, "[ESTADO] $estado -> $novo")
        estado = novo
        tEstado = now()
        situacaoAtual = "---"
        tViaLimpa = null
        tipoConfirmacao = null
        tSemPerdido = null
        upgradeSemCount = 0
        upgradeFaixaCount = 0
        reconFrames = 0
        reconSemaforo = 0
        reconFaixa = 0
        faixaAlinhada = false
        faixaCentroX = -1f
    }

    // ================================================================
    //  PROCESSAR FRAME
    // ================================================================

    fun processar(
        veiculosReais: Map<Int, VeiculoInfo>,
        nSemId: Int,
        semCorFrame: String,
        faixaDet: Boolean,
        faixaCx: Float,
        wf: Int,
        hf: Int,
        risk: CrossingRiskAssessment? = null,
        monitorHazards: Boolean = false,
        guidedVerification: Boolean = false
    ) {
        val alertsEnabled = estado != EST_INATIVO || monitorHazards
        // Durante a travessia guiada, sinal/perigo são falados UMA vez pela própria sequência
        // ("Sinal vermelho. Aguarde." / "Atenção! Veículo se aproximando. Aguarde."): aqui só observa.
        fun alert(topic: String, state: String, text: String) =
            if (guidedVerification) voz.observarEstado(topic, state) else { voz.falarEstado(topic, state, text, VoicePriority.SAFETY); true }
        currentRisk = risk
        semCorAtual = semCorFrame
        faixaCentroX = faixaCx
        if (alertsEnabled && faixaDet && semCorFrame == "VERMELHO")
            alert("scene_light", "ped_red", config.tr("voice_red_light_wait"))
        else if (semCorFrame == "NENHUM") voz.observarEstado("scene_light", "not_observed", 1500)
        val conflicting = risk?.vehicles?.firstOrNull { it.conflicting }
        if (alertsEnabled && risk?.reasons?.contains(RiskReason.CROSSWALK_OCCUPIED) == true) {
            alert("scene_obstruction", "occupied", RoadText.get(config.appLanguage,"crosswalk_occupied"))
        } else if (alertsEnabled && risk?.reasons?.contains(RiskReason.OBSTACLE_AHEAD) == true) {
            alert("scene_obstruction", "obstacle", RoadText.get(config.appLanguage,"obstacle_ahead"))
        } else if (alertsEnabled && risk?.reasons?.contains(RiskReason.POTENTIAL_TRAFFIC_CONFLICT) == true) {
            alert("scene_obstruction", "potential", RoadText.get(config.appLanguage,"traffic_potential"))
        } else voz.observarEstado("scene_obstruction","absent",1500)
        if (alertsEnabled && conflicting != null) {
            aproximacaoAtiva = true
            val vehicle = veiculosReais[conflicting.trackId]
            val side = if ((vehicle?.cx ?: wf / 2f) < wf / 2f) "left" else "right"
            alert("scene_approach","approaching_$side",RoadText.get(config.appLanguage,"approaching_$side"))
        } else if (voz.observarEstado("scene_approach","absent",1500)) {
            aproximacaoAtiva = false
        }

        if (guidedVerification) {
            situacaoAtual = if (risk?.danger == true || semCorFrame == "VERMELHO") "AGUARDE" else "VERIFICANDO"
            // A sequência orienta a câmera; alinhamento legado não deve pedir giro contrário.
            return
        }

        when (estado) {
            EST_INATIVO      -> { /* nada */ }
            EST_RECONHECENDO -> processarReconhecendo(semCorFrame, faixaDet)
            EST_SEMAFORO     -> processarSemaforo(veiculosReais, nSemId, semCorFrame, faixaDet)
            EST_FAIXA        -> processarFaixa(veiculosReais, nSemId, semCorFrame, faixaDet, faixaCx, wf)
            EST_VIA_LIVRE    -> processarViaLivre(veiculosReais, nSemId, semCorFrame, faixaDet)
        }
    }

    // Não transformar veículos laterais/ocultos em uma afirmação de ausência na imagem.
    private fun trafficUnresolved(): Boolean = currentRisk?.let {
        it.reasons.isNotEmpty() || !it.confidence.visualCurrent || !it.confidence.motionReliable
    } == true

    // ================================================================
    //  RECONHECENDO
    // ================================================================

    private fun processarReconhecendo(semCorFrame: String, faixaDet: Boolean) {
        situacaoAtual = "RECONHECENDO"
        reconFrames++
        if (semCorFrame != "NENHUM") reconSemaforo++
        if (faixaDet) reconFaixa++

        val ps = if (reconFrames > 0) reconSemaforo.toFloat() / reconFrames else 0f
        val pf = if (reconFrames > 0) reconFaixa.toFloat() / reconFrames else 0f

        val early = reconFrames >= config.reconEarlyMinFrames &&
                (ps >= config.reconEarlyThreshold || pf >= config.reconEarlyThreshold)
        val tempo = tempoNoEstado() >= config.tempoRecon

        if (!early && !tempo) return

        Log.i(TAG, "[RECON] frames=$reconFrames sem=${(ps*100).toInt()}% faixa=${(pf*100).toInt()}%")

        when {
            ps >= config.reconThreshold -> {
                voz.falarEstado("scene_context",EST_SEMAFORO,config.tr("voice_recon_traffic_light"))
                entrarEstado(EST_SEMAFORO)
            }
            pf >= config.reconThreshold -> {
                falarFaixa("scene_context",EST_FAIXA,config.tr("voice_recon_crosswalk"))
                entrarEstado(EST_FAIXA)
            }
            else -> {
                voz.falarEstado("scene_context",EST_VIA_LIVRE,config.tr("voice_recon_road"))
                entrarEstado(EST_VIA_LIVRE)
            }
        }
    }

    // ================================================================
    //  SEMÁFORO
    //  VERMELHO e AMARELO = mesma coisa (não pode passar)
    //  VERDE = verifica carros
    // ================================================================

    private fun processarSemaforo(
        veiculosReais: Map<Int, VeiculoInfo>,
        nSemId: Int,
        semCorFrame: String,
        faixaDet: Boolean
    ) {
        val agora = now()

        if (semCorFrame != "NENHUM") {
            tSemPerdido = null

            when (semCorFrame) {
                // VERMELHO ou AMARELO: não pode passar
                "VERMELHO", "AMARELO" -> {
                    situacaoAtual = "AGUARDE"
                    tViaLimpa = null
                    if (semCorFrame != "VERMELHO")
                        voz.falarEstado("scene_light",semCorFrame, config.tr("voice_yellow_light_wait"),VoicePriority.SAFETY)
                    voz.observarEstado("scene_traffic","blocked_by_light")
                }
                // VERDE: verifica carros
                "VERDE" -> {
                    voz.falarEstado("scene_light","VERDE",config.tr("voice_green_light"))
                    processarSemaforoVerde(veiculosReais, nSemId, agora)
                }
            }

        } else {
            // Semáforo sumiu
            if (tSemPerdido == null) tSemPerdido = agora
            if (agora - tSemPerdido!! > 4.0) {
                if (faixaDet) {
                    // Sem menção à faixa: mesma mensagem de via (regra do semáforo inalterada).
                    voz.falarEstado("scene_context",EST_FAIXA,config.tr("voice_light_lost_road"))
                    entrarEstado(EST_FAIXA)
                } else {
                    voz.falarEstado("scene_context",EST_VIA_LIVRE,config.tr("voice_light_lost_road"))
                    entrarEstado(EST_VIA_LIVRE)
                }
            }
        }

    }

    private fun processarSemaforoVerde(
        veiculosReais: Map<Int, VeiculoInfo>,
        nSemId: Int,
        agora: Double
    ) {
        val nvReais = veiculosReais.size

        if (trafficUnresolved()) {
            tViaLimpa = null; situacaoAtual = "AGUARDE"; return
        }

        // Sem carros
        if (nvReais == 0 && nSemId == 0) {
            iniciarConfirmacao("none",agora)
            if (agora - tViaLimpa!! >= TEMPO_CONFIRMAR_LIVRE_S) {
                 situacaoAtual = "VERIFICANDO"
                anunciarTransito("none",config.tr("voice_road_clear"))
            } else {
                situacaoAtual = "VERIFICANDO"
            }
            return
        }

        // Tem carros em movimento
        val temMovimento = veiculosReais.values.any { it.classif in com.travessia.segura.tracking.MotionClass.MOVING } || nSemId > 0
        if (temMovimento) {
            tViaLimpa = null
            situacaoAtual = "AGUARDE"
            anunciarTransito("moving",config.tr("voice_green_has_vehicles"),VoicePriority.SAFETY)
            return
        }

        // Todos parados
        val todosParados = veiculosReais.values.all {
            com.travessia.segura.tracking.MotionClass.isStopped(it.classif) && tracker.historicoOk(it.tid)
        }
        if (todosParados) {
            iniciarConfirmacao("stopped",agora)
            if (agora - tViaLimpa!! >= config.tempoParadoSeguro.coerceIn(1f,10f)) {
                 situacaoAtual = "VERIFICANDO"
                anunciarTransito("stopped",config.tr("voice_road_vehicles_stopped"))
            } else {
                situacaoAtual = "AGUARDE"
                anunciarTransito("stopping",config.tr("voice_green_vehicles_stopping"))
            }
        } else {
            tViaLimpa = null
            situacaoAtual = "AGUARDE"
            anunciarTransito("present",config.tr("voice_green_has_vehicles"),VoicePriority.SAFETY)
        }
    }

    // ================================================================
    //  FAIXA — sem semáforo
    // ================================================================

    private fun processarFaixa(
        veiculosReais: Map<Int, VeiculoInfo>,
        nSemId: Int,
        semCorFrame: String,
        faixaDet: Boolean,
        faixaCx: Float,
        wf: Int
    ) {
        // Upgrade para semáforo (prioridade)
        upgradeSemCount = if (semCorFrame != "NENHUM") upgradeSemCount + 1
        else maxOf(0, upgradeSemCount - 1)
        if (upgradeSemCount >= config.upgradeFrames) {
            voz.falarEstado("scene_context",EST_SEMAFORO,config.tr("voice_recon_traffic_light"))
            entrarEstado(EST_SEMAFORO)
            return
        }

        // Alinhamento
        if (faixaDet && faixaCx > 0 && wf > 0) {
            val (dir, intens) = config.calcularAlinhamentoFaixa(faixaCx, wf)
            when (dir) {
                "ESQUERDA" -> {
                    falarFaixa("scene_alignment","left_${intens > 0.35f}",
                        if (intens > 0.35f) config.tr("voice_turn_left_strong") else config.tr("voice_turn_left_light"),settleMs=1200)
                    faixaAlinhada = false
                }
                "DIREITA" -> {
                    falarFaixa("scene_alignment","right_${intens > 0.35f}",
                        if (intens > 0.35f) config.tr("voice_turn_right_strong") else config.tr("voice_turn_right_light"),settleMs=1200)
                    faixaAlinhada = false
                }
                "ALINHADO" -> {
                    falarFaixa("scene_alignment","aligned",config.tr("voice_aligned_crosswalk"),settleMs=1200)
                    faixaAlinhada = true
                }
            }
        } else if (!faixaDet) {
            faixaAlinhada = false
            voz.observarEstado("scene_alignment","missing",2000)
        }

        // Lógica de veículos
        processarLogicaVeiculos(
            veiculosReais, nSemId,
            // Mensagens de veículos sem referência à faixa (detecção da faixa inalterada).
            msgLivre      = config.tr("voice_road_clear"),
            msgAguarde    = config.tr("voice_road_wait"),
            msgParando    = config.tr("voice_vehicles_stopping"),
            msgPodePassar = config.tr("voice_road_vehicles_stopped")
        )

    }

    // ================================================================
    //  VIA LIVRE
    // ================================================================

    private fun processarViaLivre(
        veiculosReais: Map<Int, VeiculoInfo>,
        nSemId: Int,
        semCorFrame: String,
        faixaDet: Boolean
    ) {
        // Upgrade para semáforo
        upgradeSemCount = if (semCorFrame != "NENHUM") upgradeSemCount + 1
        else maxOf(0, upgradeSemCount - 1)
        if (upgradeSemCount >= config.upgradeFrames) {
            voz.falarEstado("scene_context",EST_SEMAFORO,config.tr("voice_recon_traffic_light"))
            entrarEstado(EST_SEMAFORO)
            return
        }

        // Upgrade para faixa
        upgradeFaixaCount = if (faixaDet) upgradeFaixaCount + 1
        else maxOf(0, upgradeFaixaCount - 1)
        if (upgradeFaixaCount >= config.upgradeFrames) {
            falarFaixa("scene_context",EST_FAIXA,config.tr("voice_recon_crosswalk"))
            entrarEstado(EST_FAIXA)
            return
        }

        processarLogicaVeiculos(
            veiculosReais, nSemId,
            msgLivre      = config.tr("voice_road_clear"),
            msgAguarde    = config.tr("voice_road_wait"),
            msgParando    = config.tr("voice_vehicles_stopping"),
            msgPodePassar = config.tr("voice_road_vehicles_stopped")
        )

    }

    // ================================================================
    //  LÓGICA COMUM DE VEÍCULOS
    //
    //  Sem carros:         tViaLimpa inicia → após TEMPO_CONFIRMAR_LIVRE_S → avisa livre
    //  Carros em movimento: reseta tViaLimpa → aguarda
    //  Todos parados:      tViaLimpa inicia → após TEMPO_CONFIRMAR_PARADO_S → avisa livre
    // ================================================================

    private fun processarLogicaVeiculos(
        veiculosReais: Map<Int, VeiculoInfo>,
        nSemId: Int,
        msgLivre: String,
        msgAguarde: String,
        msgParando: String,
        msgPodePassar: String
    ) {
        val agora = now()
        val nvReais = veiculosReais.size

        if (trafficUnresolved()) {
            tViaLimpa = null
            situacaoAtual = "AGUARDE"
            if (currentRisk?.reasons?.contains(RiskReason.MOTION_UNCERTAIN) == true &&
                currentRisk?.reasons?.contains(RiskReason.POTENTIAL_TRAFFIC_CONFLICT) != true)
                voz.falarEstado("scene_traffic", "uncertain", RoadText.get(config.appLanguage,"traffic_uncertain"))
            return
        }

        // Sem carros
        if (nvReais == 0 && nSemId == 0) {
            iniciarConfirmacao("none",agora)
            if (agora - tViaLimpa!! >= TEMPO_CONFIRMAR_LIVRE_S) {
                 situacaoAtual = "VERIFICANDO"
                anunciarTransito("none",msgLivre)
            } else {
                situacaoAtual = "VERIFICANDO"
                // silêncio — aguardando confirmação
            }
            return
        }

        val temMovimento = veiculosReais.values.any { it.classif in com.travessia.segura.tracking.MotionClass.MOVING } || nSemId > 0

        if (temMovimento) {
            // Carro em movimento — resetar, não pode passar
            tViaLimpa = null
            situacaoAtual = "AGUARDE"
            anunciarTransito("moving",msgAguarde,VoicePriority.SAFETY)

        } else {
            // Todos parados (com histórico ok)
            val todosParados = veiculosReais.values.isNotEmpty() &&
                    veiculosReais.values.all {
                        com.travessia.segura.tracking.MotionClass.isStopped(it.classif) && tracker.historicoOk(it.tid)
                    }

            if (todosParados) {
                iniciarConfirmacao("stopped",agora)
                val tempoParado = agora - tViaLimpa!!
                if (tempoParado >= config.tempoParadoSeguro.coerceIn(1f,10f)) {
                     situacaoAtual = "VERIFICANDO"
                    anunciarTransito("stopped",msgPodePassar)
                } else {
                    situacaoAtual = "AGUARDE"
                    anunciarTransito("stopping",msgParando)
                }
            } else {
                // Histórico insuficiente — aguardando dados
                tViaLimpa = null
                situacaoAtual = "AGUARDE"
                anunciarTransito("present",msgAguarde,VoicePriority.SAFETY)
            }
        }
    }

    private fun anunciarTransito(state: String, text: String, priority: VoicePriority = VoicePriority.CROSSING) {
        if (aproximacaoAtiva && state in setOf("moving","present")) {
            // O alerta específico já descreve o veículo: não acrescentar outro "aguarde".
            voz.observarEstado("scene_traffic","$estado:approaching")
            return
        }
        // Histórico insuficiente e movimento usam o mesmo aviso de presença.
        val speechState = if (state == "present") "moving" else state
        voz.falarEstado("scene_traffic","$estado:$speechState",text,priority,
            settleMs=if (state == "stopping") 1200 else 0)
    }

    private fun iniciarConfirmacao(tipo: String, agora: Double) {
        if (tViaLimpa == null || tipoConfirmacao != tipo) tViaLimpa = agora
        tipoConfirmacao = tipo
    }

    /**
     * Falas rotineiras sobre presença/alinhamento de faixa ficam desligadas: a detecção `crosswalk`
     * do YOLO ainda é instável. A detecção, boxes, logs e estados continuam; só a fala é silenciada.
     * Alertas de perigo (ex.: veículo no caminho) não passam por aqui.
     */
    private fun falarFaixa(topic: String, state: String, text: String, settleMs: Long = 0) {
        if (CROSSWALK_ROUTINE_SPEECH) voz.falarEstado(topic, state, text, settleMs = settleMs)
        else voz.observarEstado(topic, state, settleMs)
    }

    private fun now(): Double = android.os.SystemClock.elapsedRealtime() / 1000.0
}
