package com.travessia.segura.road

import org.junit.Test
import java.io.File

/**
 * Simulação REPRODUTÍVEL da sequência direita → esquerda → frente (resultados SIMULADOS, não do celular).
 * - YOLO a ~3,3 inferências/s (p50 de 299 ms medido no SM-S731B);
 * - o usuário leva 1,5 s para girar após cada instrução; o sensor comprova a direção enquanto ele segura;
 * - a instrução leva ~1,8 s para ser falada (aviso de fim de fala via instructionHeard); sem "pronto";
 * - cenários: limpo, com ruído de tracking (30% dos frames com TRACK_LOST/MOTION_UNCERTAIN, semente fixa)
 *   e USB sem sensor.
 * Grava métricas em build/sequence_timing.txt (antes/depois são comparados por este mesmo arquivo).
 */
class SequenceTimingSimulationTest {
    data class Result(val scenario: String, val totalMs: Long, val perDirectionMs: List<Long>, val frames: Int,
        val timeoutLike: Int, val instructions: Int, val finalStage: CrossingVerification.Stage)

    private fun simulate(scenario: String, sensor: Boolean, noise: Double, seed: Long = 42): Result {
        val rnd = java.util.Random(seed)
        val changes = mutableListOf<Pair<Long, CrossingVerification.Stage>>()
        var now = 0L
        val c = CrossingVerification(onChange = { changes += now to it })
        c.start(0)
        var stageSince = 0L
        var lastStage = c.stage
        var frames = 0
        var readySaid = false
        while (now < 90_000 && !c.finished) {
            now += 300
            if (c.stage != lastStage) { lastStage = c.stage; stageSince = now; readySaid = false }
            val awaiting = c.stage.name.startsWith("AWAIT_")
            // Fluxo atual: a instrução leva ~1,8 s para ser falada; o usuário gira em 1,5 s após ouvir.
            if (awaiting && !readySaid && now - stageSince >= 1800) { readySaid = true; c.instructionHeard(now, c.stage) }
            val pointing = now - stageSince >= 1800 + 1500 || !awaiting // depois de girar, segura a direção
            val covered = sensor && pointing && c.coverageDirection != null
            val noisy = rnd.nextDouble() < noise
            val reasons = if (noisy) setOf(if (rnd.nextBoolean()) RiskReason.TRACK_LOST else RiskReason.MOTION_UNCERTAIN) else emptySet()
            val risk = CrossingRiskAssessment(reasons, emptyList(), SceneConfidence(true, !noisy, covered))
            c.observe(risk, SceneEvidence(CrosswalkStatus.UNCERTAIN, null, SignalApplicability.NOT_OBSERVED, now), null, now)
            frames++
        }
        val awaitTimes = changes.filter { it.second.name.startsWith("AWAIT_") }.map { it.first }
        val end = changes.lastOrNull()?.first ?: now
        val per = (awaitTimes + end).zipWithNext { a, b -> b - a }
        // "Timeout": etapa de observação que durou ~>= 3 s (janela não completou por evidência).
        val verifies = changes.zipWithNext().filter { it.first.second.name.startsWith("VERIFY_") }
            .count { it.second.first - it.first.first >= 3000 }
        return Result(scenario, end, per, frames, verifies, awaitTimes.size, c.stage)
    }

    @Test fun recordSequenceTiming() {
        val results = listOf(
            simulate("camerax_limpo", sensor = true, noise = 0.0),
            simulate("camerax_ruido_30pct", sensor = true, noise = 0.3),
            simulate("usb_sem_sensor", sensor = false, noise = 0.0))
        val text = results.joinToString("\n") {
            "${it.scenario}: total=${it.totalMs}ms por_direcao=${it.perDirectionMs} frames=${it.frames} " +
                "verificacoes_longas=${it.timeoutLike} instrucoes=${it.instructions} final=${it.finalStage}"
        }
        File("build").mkdirs()
        File("build/sequence_timing.txt").writeText(text + "\n")
        println(text)
    }
}
