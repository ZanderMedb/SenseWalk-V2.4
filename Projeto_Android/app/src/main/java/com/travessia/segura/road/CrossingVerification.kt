package com.travessia.segura.road

/** Revisão por direções, separada dos estados do capturador e da máquina de trânsito legada.
 * A confirmação do botão declara onde o usuário posicionou a câmera; não mede o ângulo.
 */
class CrossingVerification(private val onChange: (Stage) -> Unit = {}) {
    enum class Stage { IDLE, ANALYZING, AWAIT_RIGHT, VERIFY_RIGHT, AWAIT_LEFT, VERIFY_LEFT,
        AWAIT_FRONT, VERIFY_FRONT, FAVORABLE_OBSERVATION, MONITORING, DANGER, INCONCLUSIVE }

    @Volatile var stage = Stage.IDLE; private set
    /** Botão é declaração do usuário, NÃO evidência de orientação/cobertura. */
    val directionConfirmed: Boolean get() = false
    val hasCrosswalk: Boolean get() = withCrosswalk
    val pedestrianGreenObserved: Boolean get() = signalWasGreen
    private var startedAt = 0L
    private var firstSample = -1L
    private var lastSample = -1L
    private var sampleCount = 0
    private var rightAt = -1L
    private var leftAt = -1L
    private var rightCoverage = false
    private var leftCoverage = false
    private var windowCoverage = true
    private var withCrosswalk = false
    private var signalWasGreen = false
    private var unsupportedGeometry = false
    private var unsupportedDirection = false
    private var lastObservation = -1L
    val cannotAssessWithoutCrosswalk: Boolean get() = unsupportedGeometry
    val cannotVerifyDirection: Boolean get() = unsupportedDirection

    @Synchronized fun start(now: Long) {
        reset()
        startedAt = now
        change(Stage.ANALYZING)
    }

    @Synchronized fun cancel() {
        rightAt = -1; leftAt = -1; resetSamples()
        change(Stage.IDLE)
    }

    @Synchronized fun invalidate() {
        if (stage == Stage.IDLE) return
        rightAt = -1; leftAt = -1; resetSamples()
        change(Stage.INCONCLUSIVE)
    }

    /** Botão acessível: inicia uma janela NOVA de observação; amostras anteriores não contam. */
    @Synchronized fun confirmCameraDirection(now: Long) {
        val next = when (stage) {
            Stage.AWAIT_RIGHT -> Stage.VERIFY_RIGHT
            Stage.AWAIT_LEFT -> Stage.VERIFY_LEFT
            Stage.AWAIT_FRONT -> Stage.VERIFY_FRONT
            else -> return
        }
        startedAt = now
        resetSamples()
        change(next)
    }

    @Synchronized fun observe(risk: CrossingRiskAssessment, scene: SceneEvidence,
        road: RoadSegmentationResult?, capturedAtMs: Long) {
        if (stage == Stage.IDLE) return
        // Um frame anterior ao comando não comprova a direção pedida depois da captura.
        if (capturedAtMs < startedAt || capturedAtMs <= lastObservation) return
        lastObservation = capturedAtMs
        if (!risk.confidence.visualCurrent || scene.timestampMs != capturedAtMs) {
            invalidate(); return
        }
        if (!withCrosswalk && stage != Stage.ANALYZING &&
            scene.crosswalk == CrosswalkStatus.CONFIRMED && !unsupportedGeometry) {
            withCrosswalk = true
            signalWasGreen = scene.signal == SignalApplicability.PEDESTRIAN_GREEN
            rightAt = -1; leftAt = -1; resetSamples()
            startedAt = capturedAtMs
            if (stage == Stage.AWAIT_RIGHT) onChange(Stage.AWAIT_RIGHT)
            else change(Stage.AWAIT_RIGHT)
        }
        if (withCrosswalk && scene.signal == SignalApplicability.PEDESTRIAN_GREEN) signalWasGreen = true
        if (risk.danger || scene.signal == SignalApplicability.PEDESTRIAN_RED) {
            rightAt = -1; leftAt = -1; resetSamples()
            change(Stage.DANGER)
            return
        }
        if (stage == Stage.DANGER) {
            startedAt = capturedAtMs; change(Stage.AWAIT_RIGHT); return
        }
        if (stage == Stage.FAVORABLE_OBSERVATION || stage == Stage.MONITORING) {
            if (!risk.conclusive || !sidesFresh(capturedAtMs) ||
                road == null || capturedAtMs - road.timestamp !in 0..1500 || !road.possibleRoadBoundary ||
                !signalValid(scene, capturedAtMs) ||
                (withCrosswalk && (scene.crosswalk != CrosswalkStatus.CONFIRMED ||
                    (signalWasGreen && scene.signal != SignalApplicability.PEDESTRIAN_GREEN)))) {
                rightAt = -1; leftAt = -1; change(Stage.INCONCLUSIVE)
            } else change(Stage.MONITORING)
            return
        }
        if (stage == Stage.ANALYZING) {
            val roadFresh = road != null && capturedAtMs - road.timestamp in 0..1500 && road.roadDetected
            if (scene.crosswalk == CrosswalkStatus.CONFIRMED || roadFresh) {
                withCrosswalk = scene.crosswalk == CrosswalkStatus.CONFIRMED
                signalWasGreen = scene.signal == SignalApplicability.PEDESTRIAN_GREEN
                startedAt = capturedAtMs
                change(Stage.AWAIT_RIGHT)
            } else if (capturedAtMs - startedAt > 6000) change(Stage.INCONCLUSIVE)
            return
        }
        if (stage == Stage.INCONCLUSIVE) {
            if (unsupportedGeometry || unsupportedDirection) return // explicar limites sem repetir etapas impossíveis
            // Recuperação explícita, sem afrouxar critérios nem contar tentativas como evidência.
            if (risk.confidence.visualCurrent && road != null && capturedAtMs - road.timestamp in 0..1500 && road.roadDetected) {
                startedAt = capturedAtMs; change(Stage.AWAIT_RIGHT)
            }
            return
        }
        if (stage !in setOf(Stage.VERIFY_RIGHT, Stage.VERIFY_LEFT, Stage.VERIFY_FRONT)) return
        // Pode observar tráfego sem orientação comprovada, mas nunca concluir favorável.
        if (capturedAtMs - startedAt > 7000 || risk.reasons.isNotEmpty() || !risk.confidence.motionReliable) {
            if (capturedAtMs - startedAt > 7000) retryCurrentDirection(capturedAtMs)
            else resetSamples()
            return
        }
        if (capturedAtMs <= lastSample) return
        if (lastSample >= 0 && capturedAtMs - lastSample > 1500) resetSamples()
        if (firstSample < 0) firstSample = capturedAtMs
        lastSample = capturedAtMs
        windowCoverage = windowCoverage && risk.confidence.coverageVerified
        sampleCount++
        if (sampleCount < 3 || capturedAtMs - firstSample < 1500) return
        if (!windowCoverage) {
            unsupportedDirection = true
            invalidate()
            return
        }
        when (stage) {
            Stage.VERIFY_RIGHT -> { rightAt = capturedAtMs; rightCoverage = windowCoverage; startedAt = capturedAtMs; change(Stage.AWAIT_LEFT) }
            Stage.VERIFY_LEFT -> { leftAt = capturedAtMs; leftCoverage = windowCoverage; startedAt = capturedAtMs; change(Stage.AWAIT_FRONT) }
            Stage.VERIFY_FRONT -> {
                val roadFresh = road != null && capturedAtMs - road.timestamp in 0..1500 && road.possibleRoadBoundary
                val sidesFresh = sidesFresh(capturedAtMs)
                val signalOk = signalValid(scene, capturedAtMs)
                // Sem faixa não há análise da legalidade/geometria da travessia: não concluir.
                if (!withCrosswalk) unsupportedGeometry = true
                if (withCrosswalk && scene.crosswalk == CrosswalkStatus.CONFIRMED && roadFresh && sidesFresh && signalOk &&
                    rightCoverage && leftCoverage && windowCoverage) {
                    change(Stage.FAVORABLE_OBSERVATION)
                } else {
                    startedAt = capturedAtMs
                    change(if (!sidesFresh) Stage.AWAIT_RIGHT else Stage.INCONCLUSIVE)
                }
            }
            else -> Unit
        }
        resetSamples()
    }

    private fun sidesFresh(now: Long): Boolean = rightAt >= 0 && leftAt >= 0 &&
        now - rightAt in 0..12000 && now - leftAt in 0..12000

    private fun signalValid(scene: SceneEvidence, now: Long): Boolean =
        (!signalWasGreen && scene.signal == SignalApplicability.NOT_APPLICABLE_CONFIRMED) ||
            (scene.signal == SignalApplicability.PEDESTRIAN_GREEN &&
                scene.greenObservedAtMs >= 0 && now - scene.greenObservedAtMs >= 400)

    private fun retryCurrentDirection(now: Long) {
        startedAt = now; resetSamples()
        change(when (stage) {
            Stage.VERIFY_RIGHT -> Stage.AWAIT_RIGHT
            Stage.VERIFY_LEFT -> Stage.AWAIT_LEFT
            else -> Stage.AWAIT_FRONT
        })
    }

    private fun change(next: Stage) { if (stage != next) { stage = next; onChange(next) } }
    private fun resetSamples() { firstSample = -1; lastSample = -1; sampleCount = 0; windowCoverage = true }
    private fun reset() { rightAt = -1; leftAt = -1; rightCoverage = false; leftCoverage = false; lastObservation = -1;
        signalWasGreen = false; withCrosswalk = false; unsupportedGeometry = false; unsupportedDirection = false; resetSamples() }
}
