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

/**
 * Máquina de estados simplificada e corrigida.
 *
 * SEMÁFORO (prioridade máxima):
 *   VERMELHO ou AMARELO → não pode passar (amarelo = vermelho)
 *   VERDE → verifica carros → se ok, pode passar
 *
 * FAIXA (sem semáforo):
 *   Sem carros → aguarda TEMPO_CONFIRMAR_LIVRE_S → avisa que pode passar
 *   Carros em movimento → aguarda
 *   Carros pararam/saíram → aguarda TEMPO_CONFIRMAR_PARADO_S → avisa que pode passar
 *
 * VIA LIVRE (sem faixa e sem semáforo):
 *   Mesma lógica da FAIXA
 */
class StateMachine(
    private val config: AppConfig,
    private val voz: VoiceSystem,
    private val tracker: Rastreador
) {
    companion object {
        private const val TAG = "StateMachine"
        // Tempo de confirmação — "via realmente limpa". Ajuste depois dos testes.
        private const val TEMPO_CONFIRMAR_LIVRE_S = 3.0
        private const val TEMPO_CONFIRMAR_PARADO_S = 3.0
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
    private var semCorAnterior = "NENHUM"
    private var tSemPerdido: Double? = null

    // Timer de via limpa / carros parados
    private var tViaLimpa: Double? = null

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
        Log.i(TAG, "[ESTADO] $estado -> $novo")
        estado = novo
        tEstado = now()
        situacaoAtual = "---"
        tViaLimpa = null
        semCorAnterior = "NENHUM"
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
        hf: Int
    ) {
        semCorAtual = semCorFrame
        faixaCentroX = faixaCx

        when (estado) {
            EST_INATIVO      -> { /* nada */ }
            EST_RECONHECENDO -> processarReconhecendo(semCorFrame, faixaDet, veiculosReais.size)
            EST_SEMAFORO     -> processarSemaforo(veiculosReais, nSemId, semCorFrame, faixaDet)
            EST_FAIXA        -> processarFaixa(veiculosReais, nSemId, semCorFrame, faixaDet, faixaCx, wf)
            EST_VIA_LIVRE    -> processarViaLivre(veiculosReais, nSemId, semCorFrame, faixaDet)
        }
    }

    // ================================================================
    //  RECONHECENDO
    // ================================================================

    private fun processarReconhecendo(semCorFrame: String, faixaDet: Boolean, nvReais: Int) {
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
                voz.forcar("recon_resultado", config.tr("voice_recon_traffic_light"))
                entrarEstado(EST_SEMAFORO)
            }
            pf >= config.reconThreshold -> {
                voz.forcar("recon_resultado", config.tr("voice_recon_crosswalk"))
                entrarEstado(EST_FAIXA)
            }
            else -> {
                voz.forcar("recon_resultado", config.tr("voice_recon_road"))
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

            // Anunciar mudança de cor
            if (semCorFrame != semCorAnterior && semCorAnterior != "NENHUM") {
                when (semCorFrame) {
                    "VERDE"             -> voz.forcar("semaforo_cor", config.tr("voice_green_light"))
                    "VERMELHO","AMARELO" -> voz.forcar("semaforo_cor", config.tr("voice_red_light_stop"))
                }
            }
            semCorAnterior = semCorFrame

            when (semCorFrame) {
                // VERMELHO ou AMARELO: não pode passar
                "VERMELHO", "AMARELO" -> {
                    situacaoAtual = "AGUARDE"
                    tViaLimpa = null
                    voz.falar("semaforo_aguarde", config.tr("voice_red_light_wait"))
                }
                // VERDE: verifica carros
                "VERDE" -> processarSemaforoVerde(veiculosReais, nSemId, agora)
            }

        } else {
            // Semáforo sumiu
            if (tSemPerdido == null) tSemPerdido = agora
            if (agora - tSemPerdido!! > 4.0) {
                if (faixaDet) {
                    voz.forcar("upgrade", config.tr("voice_light_lost_crosswalk"))
                    entrarEstado(EST_FAIXA)
                } else {
                    voz.forcar("upgrade", config.tr("voice_light_lost_road"))
                    entrarEstado(EST_VIA_LIVRE)
                }
            }
        }

        // Heartbeat
        if (voz.tempoSilencio() >= config.heartbeatInterval) {
            val msg = when (semCorFrame) {
                "VERDE"             -> config.tr("voice_green_light")
                "VERMELHO","AMARELO" -> config.tr("voice_red_light_wait")
                else                -> config.tr("voice_looking_for_light")
            }
            voz.falar("heartbeat", msg)
        }
    }

    private fun processarSemaforoVerde(
        veiculosReais: Map<Int, VeiculoInfo>,
        nSemId: Int,
        agora: Double
    ) {
        val nvReais = veiculosReais.size

        // Sem carros
        if (nvReais == 0 && nSemId == 0) {
            if (tViaLimpa == null) tViaLimpa = agora
            if (agora - tViaLimpa!! >= TEMPO_CONFIRMAR_LIVRE_S) {
                situacaoAtual = "SEGURO"
                voz.falar("semaforo_pode", config.tr("voice_green_road_clear"))
            } else {
                situacaoAtual = "VERIFICANDO"
            }
            return
        }

        // Tem carros em movimento
        val temMovimento = veiculosReais.values.any { it.classif != "PARADO" } || nSemId > 0
        if (temMovimento) {
            tViaLimpa = null
            situacaoAtual = "AGUARDE"
            voz.falar("semaforo_veiculo", config.tr("voice_green_has_vehicles"))
            return
        }

        // Todos parados
        val todosParados = veiculosReais.values.all {
            it.classif == "PARADO" && tracker.historicoOk(it.tid)
        }
        if (todosParados) {
            if (tViaLimpa == null) tViaLimpa = agora
            if (agora - tViaLimpa!! >= TEMPO_CONFIRMAR_PARADO_S) {
                situacaoAtual = "SEGURO"
                voz.falar("semaforo_pode", config.tr("voice_green_vehicles_stopped"))
            } else {
                situacaoAtual = "AGUARDE"
                voz.falar("semaforo_veiculo", config.tr("voice_green_vehicles_stopping"))
            }
        } else {
            tViaLimpa = null
            situacaoAtual = "AGUARDE"
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
            voz.forcar("upgrade", config.tr("voice_recon_traffic_light"))
            entrarEstado(EST_SEMAFORO)
            return
        }

        // Alinhamento
        if (faixaDet && faixaCx > 0 && wf > 0) {
            val (dir, intens) = config.calcularAlinhamentoFaixa(faixaCx, wf)
            when (dir) {
                "ESQUERDA" -> {
                    voz.falar("faixa_alinhamento",
                        if (intens > 0.35f) config.tr("voice_turn_left_strong")
                        else config.tr("voice_turn_left_light"))
                    faixaAlinhada = false
                }
                "DIREITA" -> {
                    voz.falar("faixa_alinhamento",
                        if (intens > 0.35f) config.tr("voice_turn_right_strong")
                        else config.tr("voice_turn_right_light"))
                    faixaAlinhada = false
                }
                "ALINHADO" -> {
                    if (!faixaAlinhada) {
                        voz.falar("faixa_alinhamento", config.tr("voice_aligned_crosswalk"))
                        faixaAlinhada = true
                    }
                }
            }
        } else if (!faixaDet) {
            faixaAlinhada = false
        }

        // Lógica de veículos
        processarLogicaVeiculos(
            veiculosReais, nSemId,
            msgLivre      = config.tr("voice_crosswalk_clear"),
            msgAguarde    = config.tr("voice_crosswalk_wait"),
            msgParando    = config.tr("voice_vehicles_stopping"),
            msgPodePassar = config.tr("voice_crosswalk_vehicles_stopped"),
            prefixo       = "faixa"
        )

        // Heartbeat
        if (voz.tempoSilencio() >= config.heartbeatInterval) {
            val nMov = veiculosReais.values.count { it.classif != "PARADO" }
            voz.falar("heartbeat",
                if (nMov > 0) config.trFormat("voice_monitor_crosswalk_moving", nMov)
                else config.tr("voice_monitor_crosswalk"))
        }
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
            voz.forcar("upgrade", config.tr("voice_recon_traffic_light"))
            entrarEstado(EST_SEMAFORO)
            return
        }

        // Upgrade para faixa
        upgradeFaixaCount = if (faixaDet) upgradeFaixaCount + 1
        else maxOf(0, upgradeFaixaCount - 1)
        if (upgradeFaixaCount >= config.upgradeFrames) {
            voz.forcar("upgrade", config.tr("voice_recon_crosswalk"))
            entrarEstado(EST_FAIXA)
            return
        }

        processarLogicaVeiculos(
            veiculosReais, nSemId,
            msgLivre      = config.tr("voice_road_clear"),
            msgAguarde    = config.tr("voice_road_wait"),
            msgParando    = config.tr("voice_vehicles_stopping"),
            msgPodePassar = config.tr("voice_road_vehicles_stopped"),
            prefixo       = "via"
        )

        // Heartbeat
        if (voz.tempoSilencio() >= config.heartbeatInterval) {
            val nMov = veiculosReais.values.count { it.classif != "PARADO" }
            voz.falar("heartbeat",
                if (nMov > 0) config.trFormat("voice_monitor_road_moving", nMov)
                else config.tr("voice_monitor_road"))
        }
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
        msgPodePassar: String,
        prefixo: String
    ) {
        val agora = now()
        val nvReais = veiculosReais.size

        // Sem carros
        if (nvReais == 0 && nSemId == 0) {
            if (tViaLimpa == null) tViaLimpa = agora
            if (agora - tViaLimpa!! >= TEMPO_CONFIRMAR_LIVRE_S) {
                situacaoAtual = "SEGURO"
                voz.falar("${prefixo}_livre", msgLivre)
            } else {
                situacaoAtual = "VERIFICANDO"
                // silêncio — aguardando confirmação
            }
            return
        }

        val temMovimento = veiculosReais.values.any { it.classif != "PARADO" } || nSemId > 0

        if (temMovimento) {
            // Carro em movimento — resetar, não pode passar
            tViaLimpa = null
            situacaoAtual = "AGUARDE"
            voz.falar("${prefixo}_movimento", msgAguarde)

        } else {
            // Todos parados (com histórico ok)
            val todosParados = veiculosReais.values.isNotEmpty() &&
                    veiculosReais.values.all {
                        it.classif == "PARADO" && tracker.historicoOk(it.tid)
                    }

            if (todosParados) {
                if (tViaLimpa == null) tViaLimpa = agora
                val tempoParado = agora - tViaLimpa!!
                if (tempoParado >= TEMPO_CONFIRMAR_PARADO_S) {
                    situacaoAtual = "SEGURO"
                    voz.falar("${prefixo}_parados", msgPodePassar)
                } else {
                    situacaoAtual = "AGUARDE"
                    voz.falar("${prefixo}_parando", msgParando)
                }
            } else {
                // Histórico insuficiente — aguardando dados
                tViaLimpa = null
                situacaoAtual = "AGUARDE"
            }
        }
    }

    private fun now(): Double = System.currentTimeMillis() / 1000.0
}
