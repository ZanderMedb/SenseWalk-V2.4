package com.travessia.segura.road

import com.travessia.segura.vision.SparseOpticalFlowEstimator.MotionEstimate
import kotlin.math.abs

/**
 * Sequência guiada por áudio: direita -> esquerda -> frente -> resultado.
 *
 * Sem botão e sem "pronto" obrigatórios ("aponte e mantenha"):
 *  - AWAIT_X: instrução curta falada uma vez. A observação começa sozinha após [turnMs] (tempo de girar),
 *    ou antes, quando o sensor comprova a direção ou o movimento visual estabiliza. "Pronto"/"Continuar"
 *    continuam opcionais e só antecipam.
 *  - VERIFY_X: >= 3 frames limpos ao longo de [verifyWindowMs] encerram a etapa e pedem a próxima direção
 *    no mesmo frame. Sem essa evidência em [verifyTimeoutMs]: uma nova tentativa e depois avança como
 *    inconclusiva. Um segundo "Pronto" aqui é ignorado. Nunca volta para a direita sozinha.
 *
 * Estados distintos por direção: solicitada, movimento detectado, cobertura confirmada,
 * cobertura inconclusiva. Movimento da imagem NÃO é cobertura/ângulo; "Pronto"/botão também não.
 * Condição favorável exige cobertura confirmada nas três direções (no Android atual não há
 * produtor de cobertura calibrada, portanto o resultado real permanece inconclusivo).
 * A FFNet não participa desta decisão.
 */
class CrossingVerification(
    private val onChange: (Stage) -> Unit = {},
    @Suppress("unused") private val onReprompt: (Stage) -> Unit = {}, // fluxo por tempo: sem lembretes de "pronto"
    /** Giro para o lado oposto ao pedido (sensor da câmera nativa): uma dica por etapa. */
    private val onWrongDirection: (Stage) -> Unit = {},
    /** Observação inconclusiva: pedir UMA nova observação ("Não consegui confirmar. Aponte novamente."). */
    private val onRetry: (Stage) -> Unit = {},
    /**
     * Tempo para girar e estabilizar após a instrução (inclui a fala curta). Depois disso a observação
     * começa sozinha, sem "pronto". Com orientação comprovada pelo sensor começa antes.
     */
    private val turnMs: Long = 4000,
    private val minTurnMs: Long = 1200,
    /** Tempo para girar e segurar depois que a instrução TERMINOU de ser falada. */
    private val turnAfterSpeechMs: Long = 1500,
    /** Evidência mínima de "sem conflito observado": >= 3 frames limpos e cobertos ao longo de 1,5 s. */
    private val verifyWindowMs: Long = 1500,
    /** Sem essa evidência em 3 s, a observação é INCONCLUSIVA (uma nova tentativa, depois avança). */
    private val verifyTimeoutMs: Long = 3000,
    private val maxRetries: Int = 1,
    /** Com orientação medida: tempo máximo esperando o usuário chegar à direção, e quando dar a dica. */
    private val coverageWaitMs: Long = 12000,
    private val coverageHintMs: Long = 5000,
    private val dangerClearMs: Long = 2000,
    /**
     * Janela inicial (câmera à frente) para reconhecer o cenário: semáforo de pedestres ou faixa sem semáforo.
     * 0 = decide no primeiro quadro (testes antigos). O app usa ~1,5 s, estendida até [contextMaxMs] quando
     * há algo parecido com faixa/sinal (evidência ainda amadurecendo). Sem cenário confiável: fluxo direcional.
     */
    private val contextWindowMs: Long = 0,
    private val contextMaxMs: Long = 3000,
    /** Sinal vermelho sem ser visto por este tempo: volta à verificação convencional (não presume verde). */
    private val redLostMs: Long = 2500,
    /** Verde perdido (falha de detecção) tolerado por este tempo; depois o estado verde é invalidado. */
    private val greenLossMs: Long = 1500,
    /** Observação mínima dos veículos com sinal verde / faixa (quadros limpos, não um tempo fixo de espera). */
    private val signalObserveMs: Long = 2000,
    private val handObserveMs: Long = 3000,
    private val motionThreshold: Float = .15f,
    private val minMotionConfidence: Float = .30f
) {
    enum class Stage { IDLE, ANALYZING, AWAIT_RIGHT, VERIFY_RIGHT, AWAIT_LEFT, VERIFY_LEFT,
        AWAIT_FRONT, VERIFY_FRONT, FAVORABLE_OBSERVATION, MONITORING, DANGER, INCONCLUSIVE,
        /** Faixa forte + contexto SEM semáforo confirmado por fonte independente: "Levante a mão e aguarde". */
        CROSSWALK_WAIT,
        /** Sinal vermelho de pedestres (cor estável): "Sinal vermelho. Aguarde." Espera a mudança do sinal. */
        SIGNAL_RED,
        /** Sinal verde de pedestres com associação confiável: monitora os veículos (nunca anuncia segurança). */
        SIGNAL_GREEN }
    /** Cenário escolhido para esta sessão. */
    enum class Scenario { UNDECIDED, DIRECTIONAL, CROSSWALK_NO_SIGNAL, PEDESTRIAN_SIGNAL }
    enum class Direction { RIGHT, LEFT, FRONT }
    /** Estados não equivalentes: pedido != movimento != cobertura. */
    enum class DirectionStatus { NOT_REQUESTED, REQUESTED, MOTION_DETECTED, COVERAGE_CONFIRMED, COVERAGE_INCONCLUSIVE }
    /**
     * Resultado LIMITADO da observação de uma direção. Não é classificação de segurança física:
     * NO_CONFLICT_OBSERVED só diz que, naquele intervalo, a direção foi coberta com frames recentes e
     * nenhum conflito foi identificado. Nunca vira "pode atravessar".
     */
    enum class ObservationOutcome { NOT_OBSERVED, NO_CONFLICT_OBSERVED, POTENTIAL_DANGER, INCONCLUSIVE }

    @Volatile var stage = Stage.IDLE; private set
    /** Botão/voz é declaração do usuário, NÃO evidência de orientação/cobertura. */
    val directionConfirmed: Boolean get() = false
    val hasCrosswalk: Boolean get() = withCrosswalk
    val pedestrianGreenObserved: Boolean get() = signalWasGreen
    var handSignalApplicable: Boolean = false; private set
    val cannotAssessWithoutCrosswalk: Boolean get() = unsupportedGeometry
    /** Alguma direção terminou sem cobertura confirmada (inclui "Pronto" sem evidência). */
    val cannotVerifyDirection: Boolean get() = unsupportedDirection
    /** Sessão chegou a um resultado (aguardando encerramento normal). */
    val finished: Boolean get() = stage in setOf(Stage.INCONCLUSIVE, Stage.FAVORABLE_OBSERVATION, Stage.MONITORING) ||
        (stage == Stage.DANGER && resumeStage in setOf(Stage.INCONCLUSIVE, Stage.FAVORABLE_OBSERVATION, Stage.MONITORING))
    /** Direção cuja cobertura deve ser medida neste frame (frente durante o monitoramento favorável). */
    val coverageDirection: Direction? get() = when (stage) {
        Stage.FAVORABLE_OBSERVATION, Stage.MONITORING, Stage.CROSSWALK_WAIT,
        Stage.SIGNAL_RED, Stage.SIGNAL_GREEN -> Direction.FRONT
        Stage.DANGER -> directionOf(resumeStage)
        else -> directionOf(stage)
    }
    /** Etapa de orientação atual, se houver (para comandos de voz e "Repetir"). */
    val currentDirection: Direction? get() = directionOf(if (stage == Stage.DANGER) resumeStage else stage)
    var lastVisualCameraMotion: MotionEstimate? = null; private set
    @Volatile var scenario = Scenario.UNDECIDED; private set
    /**
     * Veículos (trackId) que provocaram o último perigo. Usado para alertar UMA vez por veículo
     * (ver [com.travessia.segura.voice.DangerAlertGate]).
     */
    @Volatile var dangerTrackIds: Set<Int> = emptySet(); private set

    private val status = mutableMapOf<Direction, DirectionStatus>()
    private val outcomes = mutableMapOf<Direction, ObservationOutcome>()
    private val retries = mutableMapOf<Direction, Int>()
    fun outcome(direction: Direction): ObservationOutcome = synchronized(this) { outcomes[direction] ?: ObservationOutcome.NOT_OBSERVED }
    private val cleanUnverified = mutableSetOf<Direction>()
    private val directionsWithUncertainVehicles = mutableSetOf<Direction>()
    /** Houve veículo com movimento não confirmado (ou rastreio perdido) durante a observação desta direção. */
    fun hadUncertainVehicles(direction: Direction): Boolean = synchronized(this) { direction in directionsWithUncertainVehicles }
    /**
     * A janela da direção terminou SEM nenhum conflito/incerteza nos frames, mas a orientação não foi comprovada
     * (USB, giro insuficiente). Usado só para descrever o que foi visto; continua INCONCLUSIVE.
     */
    fun cleanButUnverified(direction: Direction): Boolean = synchronized(this) { direction in cleanUnverified }
    /**
     * As três direções terminaram como NO_CONFLICT_OBSERVED (orientação comprovada, frames recentes, sem
     * conflito) e sem perigo na sessão. Fato limitado da observação; NÃO é autorização para atravessar.
     */
    var noConflictObservedAllDirections = false; private set
    /** Frames YOLO usados na última observação concluída (métrica). */
    var lastObservationFrames = 0; private set
    private val declared = mutableSetOf<Direction>()
    fun status(direction: Direction): DirectionStatus = synchronized(this) { status[direction] ?: DirectionStatus.NOT_REQUESTED }
    fun declaredByUser(direction: Direction): Boolean = synchronized(this) { direction in declared }

    private var startedAt = 0L
    private var firstSample = -1L
    private var lastSample = -1L
    private var sampleCount = 0
    private var rightAt = -1L
    private var leftAt = -1L
    private var windowCoverage = true
    private var withCrosswalk = false
    private var signalWasGreen = false
    private var unsupportedGeometry = false
    private var unsupportedDirection = false
    private var lastObservation = -1L
    private var accumulatedShift = 0f
    private var unstableFrames = 0
    private var stableFrames = 0
    private var motionSeen = false
    private var resumeStage = Stage.IDLE
    private var lastDangerAt = -1L
    private var dangerDuringSequence = false
    private var coverageFrames = 0
    private var wrongDirectionFrames = 0
    private var wrongDirectionHinted = false
    private var coverageSeenInWindow = false
    private var uncleanSeenInWindow = false
    private var windowFrames = 0
    private var instructionHeardAt = -1L
    private var coverageHintGiven = false
    private var lastRedAt = -1L
    private var lastGreenValidAt = -1L
    private var signalResume: Direction? = null

    @Synchronized fun start(now: Long) {
        reset()
        startedAt = now
        change(Stage.ANALYZING)
    }

    /** "Analisar novamente": estado temporal limpo, começando pela direita. */
    @Synchronized fun restart(now: Long) { reset(); startedAt = now; stage = Stage.IDLE; enterAwait(Direction.RIGHT, now) }

    @Synchronized fun cancel() {
        reset()
        change(Stage.IDLE)
    }

    /**
     * Invalidação forte (troca/erro de câmera): resultados antigos descartados, sem reinício automático.
     * @return true somente se a etapa mudou (invalidações repetidas não devem gerar nova fala).
     */
    @Synchronized fun invalidate(): Boolean {
        handSignalApplicable = false
        if (stage == Stage.IDLE || stage == Stage.INCONCLUSIVE) return false
        rightAt = -1; leftAt = -1; resetSamples()
        for (d in Direction.values()) if (status[d] == DirectionStatus.COVERAGE_CONFIRMED) status[d] = DirectionStatus.COVERAGE_INCONCLUSIVE
        resumeStage = Stage.IDLE
        change(Stage.INCONCLUSIVE)
        return true
    }

    /** Frame atrasado isolado: descarta a janela atual sem encerrar a sequência. */
    @Synchronized fun degrade() {
        when (stage) {
            Stage.FAVORABLE_OBSERVATION, Stage.MONITORING -> { handSignalApplicable = false; change(Stage.INCONCLUSIVE) }
            Stage.VERIFY_RIGHT, Stage.VERIFY_LEFT, Stage.VERIFY_FRONT -> resetSamples()
            else -> Unit
        }
    }

    /**
     * "Pronto"/botão. Em AWAIT_X inicia uma janela NOVA de observação (amostras anteriores não contam).
     * Em VERIFY_X, sem evidência suficiente, registra a direção como não verificada e avança.
     * [expectedStage] descarta confirmações ouvidas antes de uma mudança de etapa.
     */
    @Synchronized fun confirmCameraDirection(now: Long, expectedStage: Stage? = null) {
        if (expectedStage != null && expectedStage != stage) return
        val direction = directionOf(stage) ?: return
        if (!stage.name.startsWith("AWAIT_")) return // já observando: não há segunda confirmação
        declared += direction
        beginVerify(direction, now)
    }

    /**
     * O TTS terminou de falar a instrução desta etapa: o tempo de girar conta a partir daqui
     * (fala atrasada na fila não encurta o tempo do usuário). Sem esse aviso vale [turnMs] desde a etapa.
     */
    @Synchronized fun instructionHeard(now: Long, forStage: Stage) {
        if (forStage == stage && stage.name.startsWith("AWAIT_") && instructionHeardAt < 0) instructionHeardAt = now
    }

    /** "Continuar": como "Pronto" na espera; durante a observação, encerra a janela como não verificada. */
    @Synchronized fun requestContinue(now: Long, expectedStage: Stage? = null) {
        if (expectedStage != null && expectedStage != stage) return
        if (stage == Stage.ANALYZING) { enterAwait(Direction.RIGHT, now); return }
        val direction = directionOf(stage) ?: return
        declared += direction
        if (stage.name.startsWith("AWAIT_")) beginVerify(direction, now)
        else completeDirection(direction, now, verified = false)
    }

    @Synchronized fun observe(risk: CrossingRiskAssessment, scene: SceneEvidence,
        road: RoadSegmentationResult?, capturedAtMs: Long,
        cameraMotion: MotionEstimate? = null, frameWidth: Int = 0, wrongDirection: Boolean = false,
        /** Sensor de orientação disponível e calibrado: a etapa espera a direção ser comprovada. */
        coverageAvailable: Boolean = false) {
        if (stage == Stage.IDLE) return
        // Um frame anterior ao comando não comprova a direção pedida depois da captura.
        if (capturedAtMs < startedAt || capturedAtMs <= lastObservation) return
        lastObservation = capturedAtMs
        lastVisualCameraMotion = cameraMotion
        if (scene.timestampMs != capturedAtMs) { invalidate(); return }
        // Expiração é avaliada na decisão, não no instante anterior à inferência YOLO.
        val decisionAtMs = capturedAtMs + risk.confidence.frameAgeMs
        if (scene.crosswalk == CrosswalkStatus.CONFIRMED && !withCrosswalk) {
            // Faixa confirmada no meio da sequência NÃO reinicia as etapas.
            withCrosswalk = true
            signalWasGreen = scene.signal == SignalApplicability.PEDESTRIAN_GREEN
        }
        if (withCrosswalk && scene.signal == SignalApplicability.PEDESTRIAN_GREEN) signalWasGreen = true

        val redStable = scene.signal == SignalApplicability.PEDESTRIAN_RED && scene.signalColorStable
        if (scene.signal == SignalApplicability.PEDESTRIAN_RED) lastRedAt = capturedAtMs
        // Sinal vermelho de pedestres com cor estável: aguardar a mudança do sinal, sem roteiro direita/esquerda.
        // (Vermelho é o lado conservador: basta a associação geométrica + cor estável.)
        if (redStable && stage != Stage.SIGNAL_RED && stage != Stage.INCONCLUSIVE) {
            signalResume = currentDirection ?: signalResume
            if (stage == Stage.DANGER) resumeStage = Stage.IDLE
            handSignalApplicable = false
            for (d in Direction.values()) if (outcomes[d] == ObservationOutcome.NO_CONFLICT_OBSERVED)
                outcomes[d] = ObservationOutcome.INCONCLUSIVE
            scenario = Scenario.PEDESTRIAN_SIGNAL
            resetSamples()
            change(Stage.SIGNAL_RED)
            return
        }
        if (stage == Stage.SIGNAL_RED) {
            // Veículos passando no vermelho do pedestre são esperados: o usuário já está aguardando.
            if (scene.signal == SignalApplicability.PEDESTRIAN_GREEN && scene.signalReliable && risk.confidence.visualCurrent) {
                enterGreen(capturedAtMs); return
            }
            if (lastRedAt >= 0 && capturedAtMs - lastRedAt >= redLostMs) {
                // Sinal deixou de ser visto (sem verde confiável): verificação convencional, sem presumir nada.
                scenario = Scenario.DIRECTIONAL
                enterAwait(signalResume ?: Direction.RIGHT, capturedAtMs)
            }
            return
        }

        // Perigo tem prioridade em qualquer etapa e é mantido com histerese (sem oscilar a fala).
        if (risk.danger || scene.signal == SignalApplicability.PEDESTRIAN_RED) {
            lastDangerAt = capturedAtMs
            if (stage != Stage.DANGER) {
                dangerTrackIds = risk.vehicles.filter { it.conflicting || it.obstacle ||
                    it.classification in com.travessia.segura.tracking.MotionClass.CONFLICT }.map { it.trackId }.toSet()
                resumeStage = stage
                handSignalApplicable = false
                dangerDuringSequence = true
                // Perigo novo invalida conclusões anteriores: nenhuma direção fica "sem conflito".
                coverageDirection?.let { outcomes[it] = ObservationOutcome.POTENTIAL_DANGER }
                for (d in Direction.values()) if (outcomes[d] == ObservationOutcome.NO_CONFLICT_OBSERVED)
                    outcomes[d] = ObservationOutcome.INCONCLUSIVE
                cleanUnverified.clear()
                rightAt = -1; leftAt = -1
                resetSamples()
                change(Stage.DANGER)
            }
            return
        }
        if (stage == Stage.DANGER) {
            if (capturedAtMs - lastDangerAt < dangerClearMs) return
            val direction = directionOf(resumeStage)
            val back = resumeStage
            resumeStage = Stage.IDLE
            when {
                // Cenário de sinal/faixa: volta a monitorar no mesmo cenário (sem repetir a orientação).
                back == Stage.SIGNAL_GREEN || back == Stage.CROSSWALK_WAIT -> {
                    startedAt = capturedAtMs; resetSamples(); change(back)
                }
                direction != null -> enterAwait(direction, capturedAtMs) // retoma a MESMA direção
                back == Stage.ANALYZING -> enterAwait(Direction.RIGHT, capturedAtMs)
                else -> change(Stage.INCONCLUSIVE) // favorável perdido nunca volta sozinho
            }
            return
        }

        if ((stage == Stage.FAVORABLE_OBSERVATION || stage == Stage.MONITORING) && scenario == Scenario.PEDESTRIAN_SIGNAL) {
            // Verde já observado: continua valendo só enquanto o verde confiável continua sendo visto.
            if (greenValid(scene)) lastGreenValidAt = capturedAtMs
            val contradictory = scene.signal == SignalApplicability.UNCERTAIN
            if (!risk.confidence.visualCurrent || contradictory || capturedAtMs - lastGreenValidAt > greenLossMs) {
                change(Stage.INCONCLUSIVE)
            } else change(Stage.MONITORING)
            return
        }
        if (stage == Stage.FAVORABLE_OBSERVATION || stage == Stage.MONITORING) {
            if (!risk.confidence.visualCurrent || !risk.conclusive || cameraMotion?.translationSupported == false ||
                !sidesFresh(decisionAtMs) || !signalValid(scene, capturedAtMs) ||
                (withCrosswalk && (scene.crosswalk != CrosswalkStatus.CONFIRMED ||
                    (signalWasGreen && scene.signal != SignalApplicability.PEDESTRIAN_GREEN)))) {
                handSignalApplicable = false
                change(Stage.INCONCLUSIVE)
            } else change(Stage.MONITORING)
            return
        }
        if (stage == Stage.INCONCLUSIVE) return // terminal: sem reinício automático (evita loop)
        if (!risk.confidence.visualCurrent) { resetSamples(); return }
        if (stage == Stage.ANALYZING) { decideScenario(scene, capturedAtMs); return }
        if (stage == Stage.SIGNAL_GREEN) { observeGreen(risk, scene, capturedAtMs, cameraMotion); return }
        if (stage == Stage.CROSSWALK_WAIT) { observeCrosswalk(risk, scene, capturedAtMs, cameraMotion); return }

        val direction = directionOf(stage) ?: return
        if (stage.name.startsWith("AWAIT_")) {
            // Orientação comprovada (sensor + estabilidade + frame atual) avança sem "pronto" nem botão.
            // O primeiro frame coberto já inicia a observação e conta como amostra (sem espera extra).
            if (risk.confidence.coverageVerified) beginVerify(direction, capturedAtMs)
            else {
                wrongDirectionFrames = if (wrongDirection) wrongDirectionFrames + 1 else 0
                // Dica de "lado errado" só 3 s depois de a instrução ser ouvida (o usuário ainda está saindo da
                // direção anterior); e o tracker já não marca errado quando o giro vai rumo ao alvo.
                val sinceInstruction = capturedAtMs - (if (instructionHeardAt >= 0) instructionHeardAt else startedAt)
                if (wrongDirectionFrames >= 5 && !wrongDirectionHinted && sinceInstruction >= 3000) {
                    wrongDirectionHinted = true; onWrongDirection(stage)
                }
                if (coverageAvailable) {
                    // Giro medido: NUNCA começar a analisar antes de a câmera estar na direção pedida.
                    // Uma dica no meio; sem chegar em [coverageWaitMs], segue como não confirmada (não trava).
                    val anchor = if (instructionHeardAt >= 0) instructionHeardAt else startedAt
                    val waited = capturedAtMs - anchor
                    if (waited >= coverageHintMs && !coverageHintGiven) { coverageHintGiven = true; onRetry(stage) }
                    if (waited >= coverageWaitMs) completeDirection(direction, capturedAtMs, verified = false)
                    return
                }
                observeAwait(direction, capturedAtMs, cameraMotion, frameWidth); return
            }
        }

        // VERIFY_X: pode observar tráfego sem orientação comprovada, mas nunca concluir favorável.
        windowFrames++
        if (lastSample >= 0 && capturedAtMs - lastSample > 1500) resetSamples() // lacuna: frames não são recentes
        val clean = risk.reasons.isEmpty() && risk.confidence.motionReliable &&
            (risk.vehicles.isEmpty() || cameraMotion?.translationSupported != false)
        if (risk.reasons.contains(RiskReason.MOTION_UNCERTAIN) || risk.reasons.contains(RiskReason.TRACK_LOST))
            directionsWithUncertainVehicles += direction
        // Direção alcançada agora: o tempo da observação começa aqui (quem chegou atrasado não é punido).
        if (risk.confidence.coverageVerified && !coverageSeenInWindow) startedAt = capturedAtMs
        if (risk.confidence.coverageVerified) coverageSeenInWindow = true
        // Cobertura vista e depois perdida (câmera saiu da direção) ou frame com incerteza: a sequência limpa recomeça.
        // Com sensor, quadro sem a direção comprovada (usuário ainda girando) não conta: a etapa não fecha
        // como "não confirmada" antes de a câmera chegar lá.
        if (!clean || (coverageSeenInWindow && !risk.confidence.coverageVerified) ||
            (coverageAvailable && !risk.confidence.coverageVerified)) {
            if (!clean) uncleanSeenInWindow = true
            resetSamples()
        } else {
            // Cobertura comprovada chegou depois (usuário terminou de girar): recomeçar a contagem já coberta,
            // para não concluir como "não verificada" com amostras do meio do giro.
            if (risk.confidence.coverageVerified && !windowCoverage && sampleCount > 0) resetSamples()
            if (firstSample < 0) firstSample = capturedAtMs
            lastSample = capturedAtMs
            windowCoverage = windowCoverage && risk.confidence.coverageVerified
            sampleCount++
            if (sampleCount >= 3 && capturedAtMs - firstSample >= verifyWindowMs) {
                lastObservationFrames = windowFrames
                // Evidência completa: encerra a etapa e pede a próxima direção imediatamente.
                if (!windowCoverage) cleanUnverified += direction
                if (direction == Direction.FRONT) finalEvaluation(scene, capturedAtMs, decisionAtMs, windowCoverage)
                else completeDirection(direction, capturedAtMs, verified = windowCoverage)
                return
            }
        }
        val elapsedVerify = capturedAtMs - startedAt
        // Uma sequência coberta e limpa já em andamento (usuário chegou à direção) ganha até [verifyWindowMs]
        // extra para terminar, em vez de cair numa nova tentativa no meio da contagem.
        val coveredRunInProgress = sampleCount > 0 && windowCoverage && risk.confidence.coverageVerified
        // Com sensor/giro medido e a direção ainda não alcançada: esperar o usuário chegar (até [coverageWaitMs]),
        // com UMA dica no meio ("Gire mais para..."). Antes a etapa desistia em ~6 s e o usuário chegava depois.
        if (coverageAvailable && !coverageSeenInWindow) {
            if (elapsedVerify >= coverageHintMs && !coverageHintGiven) { coverageHintGiven = true; onRetry(stage) }
            if (elapsedVerify < coverageWaitMs) return
            lastObservationFrames = windowFrames
            completeDirection(direction, capturedAtMs, verified = false)
            return
        }
        if (elapsedVerify < verifyTimeoutMs || (coveredRunInProgress && elapsedVerify < verifyTimeoutMs + verifyWindowMs)) return
        // Sem evidência suficiente: uma nova observação objetiva quando há chance (incerteza visual ou cobertura
        // perdida); sem sensor, repetir não comprova nada e a etapa avança como inconclusiva.
        lastObservationFrames = windowFrames
        if ((retries[direction] ?: 0) < maxRetries && (uncleanSeenInWindow || coverageSeenInWindow || coverageAvailable)) {
            retries[direction] = (retries[direction] ?: 0) + 1
            // Se a câmera continua na direção, ela já está "alcançada": não reiniciar o relógio de novo no próximo quadro.
            startedAt = capturedAtMs; resetSamples(); coverageSeenInWindow = risk.confidence.coverageVerified
            uncleanSeenInWindow = false; windowFrames = 0
            onRetry(stage)
            return
        }
        completeDirection(direction, capturedAtMs, verified = false)
    }

    /** Câmera à frente no início: escolhe o cenário só com evidência confiável; senão, fluxo direcional. */
    private fun decideScenario(scene: SceneEvidence, now: Long) {
        when {
            greenValid(scene) -> { enterGreen(now); return }
            scene.crosswalkStrong && scene.signal == SignalApplicability.NOT_APPLICABLE_CONFIRMED -> {
                scenario = Scenario.CROSSWALK_NO_SIGNAL
                withCrosswalk = true
                startedAt = now; resetSamples(); change(Stage.CROSSWALK_WAIT); return
            }
        }
        // Algo parecido com faixa/sinal: dar tempo à evidência temporal amadurecer (nunca decidir por 1 quadro).
        val maturing = scene.crosswalkCandidate || scene.signal in setOf(SignalApplicability.PEDESTRIAN_GREEN,
            SignalApplicability.PEDESTRIAN_RED, SignalApplicability.UNCERTAIN)
        val window = if (maturing) maxOf(contextWindowMs, contextMaxMs.takeIf { contextWindowMs > 0 } ?: 0L) else contextWindowMs
        if (now - startedAt >= window) { scenario = Scenario.DIRECTIONAL; enterAwait(Direction.RIGHT, now) }
    }

    private fun greenValid(scene: SceneEvidence) =
        scene.signal == SignalApplicability.PEDESTRIAN_GREEN && scene.signalReliable && scene.signalColorStable

    private fun enterGreen(now: Long) {
        scenario = Scenario.PEDESTRIAN_SIGNAL
        withCrosswalk = true; signalWasGreen = true
        lastGreenValidAt = now; startedAt = now; resetSamples()
        change(Stage.SIGNAL_GREEN)
    }

    /** Quadro "limpo" para os cenários de sinal/faixa: nenhum conflito, movimento confiável, imagem atual. */
    private fun cleanFrame(risk: CrossingRiskAssessment, motion: MotionEstimate?) =
        risk.reasons.isEmpty() && risk.confidence.visualCurrent && risk.confidence.motionReliable &&
            (risk.vehicles.isEmpty() || motion?.translationSupported != false)

    /**
     * Verde de pedestres: monitora os veículos. Conclui ("Verificação concluída.") só com quadros limpos
     * observados ao longo de [signalObserveMs] E o verde continuando válido; o tempo sozinho não prova nada.
     * Verde perdido/contraditório -> invalida e volta ao fluxo convencional.
     */
    private fun observeGreen(risk: CrossingRiskAssessment, scene: SceneEvidence, now: Long, motion: MotionEstimate?) {
        if (greenValid(scene)) lastGreenValidAt = now
        if (scene.signal == SignalApplicability.UNCERTAIN || now - lastGreenValidAt > greenLossMs) {
            signalWasGreen = false; withCrosswalk = false
            scenario = Scenario.DIRECTIONAL
            enterAwait(Direction.RIGHT, now); return
        }
        if (!greenValid(scene) || !cleanFrame(risk, motion)) { resetSamples(); return }
        if (lastSample >= 0 && now - lastSample > 1500) resetSamples()
        if (firstSample < 0) firstSample = now
        lastSample = now; sampleCount++
        if (sampleCount >= 3 && now - firstSample >= signalObserveMs) {
            outcomes[Direction.FRONT] = ObservationOutcome.NO_CONFLICT_OBSERVED
            resetSamples(); change(Stage.FAVORABLE_OBSERVATION)
        }
    }

    /**
     * Faixa sem semáforo: "Levante a mão e aguarde os veículos pararem." Continua analisando os veículos.
     * Veículos aparentemente parados NÃO comprovam que os outros sentidos estão livres: depois de uma
     * observação limpa da frente, pede uma observação breve das laterais (fluxo direcional).
     */
    private fun observeCrosswalk(risk: CrossingRiskAssessment, scene: SceneEvidence, now: Long, motion: MotionEstimate?) {
        if (scene.signal in setOf(SignalApplicability.PEDESTRIAN_GREEN, SignalApplicability.UNCERTAIN)) {
            // Apareceu um sinal: o contexto "sem semáforo" deixou de valer.
            scenario = Scenario.DIRECTIONAL; enterAwait(Direction.RIGHT, now); return
        }
        if (!cleanFrame(risk, motion)) { resetSamples(); return }
        if (lastSample >= 0 && now - lastSample > 1500) resetSamples()
        if (firstSample < 0) firstSample = now
        lastSample = now; sampleCount++
        if (sampleCount >= 3 && now - firstSample >= handObserveMs) {
            outcomes[Direction.FRONT] = ObservationOutcome.NO_CONFLICT_OBSERVED
            scenario = Scenario.DIRECTIONAL
            enterAwait(Direction.RIGHT, now)
        }
    }

    private fun observeAwait(direction: Direction, now: Long, motion: MotionEstimate?, frameWidth: Int) {
        if (motion != null && frameWidth > 0) {
            val shift = abs(motion.dx) / frameWidth
            if (motion.confidence >= minMotionConfidence) {
                accumulatedShift += shift
                if (shift < .02f) stableFrames++ else stableFrames = 0
            } else {
                stableFrames = 0
                // Imagem mudou além do alcance do fluxo óptico (giro rápido): indício de movimento, não de ângulo.
                if (motion.validPoints >= 8) unstableFrames++
            }
            if (accumulatedShift >= motionThreshold || unstableFrames >= 3) {
                if (!motionSeen) { motionSeen = true; status[direction] = DirectionStatus.MOTION_DETECTED }
            }
        }
        // Fluxo por tempo, sem "pronto": "aponte e mantenha". Após o tempo de giro a observação começa sozinha
        // (o sensor, quando comprova a direção antes, só antecipa). Movimento visual estável também antecipa.
        val elapsed = now - startedAt
        val heard = instructionHeardAt >= 0 && now - instructionHeardAt >= turnAfterSpeechMs
        if (heard || (motionSeen && stableFrames >= 2 && elapsed >= minTurnMs && instructionHeardAt >= 0) || elapsed >= turnMs)
            beginVerify(direction, now)
    }

    private fun beginVerify(direction: Direction, now: Long) {
        startedAt = now; resetSamples(); coverageSeenInWindow = false; uncleanSeenInWindow = false; windowFrames = 0
        coverageHintGiven = false
        change(verifyStage(direction))
    }

    private fun enterAwait(direction: Direction, now: Long) {
        startedAt = now
        resetSamples(); resetMotion()
        if ((status[direction] ?: DirectionStatus.NOT_REQUESTED) == DirectionStatus.NOT_REQUESTED)
            status[direction] = DirectionStatus.REQUESTED
        change(awaitStage(direction))
    }

    /** Registra a direção e avança uma única vez para a próxima instrução. */
    private fun completeDirection(direction: Direction, now: Long, verified: Boolean) {
        status[direction] = if (verified) DirectionStatus.COVERAGE_CONFIRMED else DirectionStatus.COVERAGE_INCONCLUSIVE
        outcomes[direction] = if (verified) ObservationOutcome.NO_CONFLICT_OBSERVED else ObservationOutcome.INCONCLUSIVE
        if (!verified) unsupportedDirection = true
        when (direction) {
            Direction.RIGHT -> { rightAt = if (verified) now else -1; enterAwait(Direction.LEFT, now) }
            Direction.LEFT -> { leftAt = if (verified) now else -1; enterAwait(Direction.FRONT, now) }
            Direction.FRONT -> { if (!withCrosswalk) unsupportedGeometry = true; resetSamples(); change(Stage.INCONCLUSIVE) }
        }
    }

    private fun finalEvaluation(scene: SceneEvidence, capturedAtMs: Long, decisionAtMs: Long, frontCoverage: Boolean) {
        status[Direction.FRONT] = if (frontCoverage) DirectionStatus.COVERAGE_CONFIRMED else DirectionStatus.COVERAGE_INCONCLUSIVE
        outcomes[Direction.FRONT] = if (frontCoverage) ObservationOutcome.NO_CONFLICT_OBSERVED else ObservationOutcome.INCONCLUSIVE
        if (!frontCoverage) unsupportedDirection = true
        // Sem faixa não há análise da legalidade/geometria da travessia: não concluir.
        if (!withCrosswalk) unsupportedGeometry = true
        val allCovered = Direction.values().all { status[it] == DirectionStatus.COVERAGE_CONFIRMED }
        noConflictObservedAllDirections = !dangerDuringSequence && sidesFresh(decisionAtMs) &&
            Direction.values().all { outcomes[it] == ObservationOutcome.NO_CONFLICT_OBSERVED }
        resetSamples()
        if (withCrosswalk && scene.crosswalk == CrosswalkStatus.CONFIRMED && sidesFresh(decisionAtMs) &&
            signalValid(scene, capturedAtMs) && allCovered && !dangerDuringSequence) {
            handSignalApplicable = scene.signal == SignalApplicability.NOT_APPLICABLE_CONFIRMED
            change(Stage.FAVORABLE_OBSERVATION)
        } else change(Stage.INCONCLUSIVE)
    }

    private fun sidesFresh(now: Long): Boolean = rightAt >= 0 && leftAt >= 0 &&
        now - rightAt in 0..12000 && now - leftAt in 0..12000

    private fun signalValid(scene: SceneEvidence, now: Long): Boolean =
        (!signalWasGreen && scene.signal == SignalApplicability.NOT_APPLICABLE_CONFIRMED) ||
            (scene.signal == SignalApplicability.PEDESTRIAN_GREEN &&
                scene.signalAssociationVerified && scene.signalColorStable &&
                scene.greenObservedAtMs >= 0 && now - scene.greenObservedAtMs >= 400)

    private fun directionOf(s: Stage): Direction? = when (s) {
        Stage.AWAIT_RIGHT, Stage.VERIFY_RIGHT -> Direction.RIGHT
        Stage.AWAIT_LEFT, Stage.VERIFY_LEFT -> Direction.LEFT
        Stage.AWAIT_FRONT, Stage.VERIFY_FRONT -> Direction.FRONT
        else -> null
    }
    private fun awaitStage(d: Direction) = when (d) { Direction.RIGHT -> Stage.AWAIT_RIGHT; Direction.LEFT -> Stage.AWAIT_LEFT; Direction.FRONT -> Stage.AWAIT_FRONT }
    private fun verifyStage(d: Direction) = when (d) { Direction.RIGHT -> Stage.VERIFY_RIGHT; Direction.LEFT -> Stage.VERIFY_LEFT; Direction.FRONT -> Stage.VERIFY_FRONT }

    private fun change(next: Stage) { if (stage != next) { stage = next; onChange(next) } }
    private fun resetSamples() { firstSample = -1; lastSample = -1; sampleCount = 0; windowCoverage = true }
    private fun resetMotion() { accumulatedShift = 0f; unstableFrames = 0; stableFrames = 0; motionSeen = false
        coverageFrames = 0; wrongDirectionFrames = 0; wrongDirectionHinted = false; instructionHeardAt = -1L
        coverageHintGiven = false }
    private fun reset() { rightAt = -1; leftAt = -1; lastObservation = -1; status.clear(); declared.clear()
        outcomes.clear(); retries.clear(); lastObservationFrames = 0; noConflictObservedAllDirections = false
        cleanUnverified.clear(); directionsWithUncertainVehicles.clear()
        lastVisualCameraMotion = null; handSignalApplicable = false; signalWasGreen = false; withCrosswalk = false
        unsupportedGeometry = false; unsupportedDirection = false; resumeStage = Stage.IDLE; lastDangerAt = -1
        dangerDuringSequence = false; resetSamples(); resetMotion()
        scenario = Scenario.UNDECIDED; dangerTrackIds = emptySet(); lastRedAt = -1; lastGreenValidAt = -1; signalResume = null }
}
