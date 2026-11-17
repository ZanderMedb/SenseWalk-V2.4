package com.travessia.segura.road

import com.travessia.segura.detection.RawDetection

enum class CrosswalkStatus { CONFIRMED, UNCERTAIN }
enum class SignalApplicability { PEDESTRIAN_GREEN, PEDESTRIAN_RED, VEHICLE_ONLY, UNCERTAIN, NOT_OBSERVED,
    /** Exige fonte independente. Nunca produzido por mera ausência de detecção YOLO. */
    NOT_APPLICABLE_CONFIRMED }

data class SceneEvidence(val crosswalk: CrosswalkStatus, val crosswalkBox: RawDetection?,
    val signal: SignalApplicability, val timestampMs: Long, val greenObservedAtMs: Long = -1L,
    /** Independent association evidence. Image proximity alone does not establish applicability. */
    val signalAssociationVerified: Boolean = false,
    /** A cor do sinal associado foi a mesma em >= 3 observações por >= 600 ms no mesmo track. */
    val signalColorStable: Boolean = false,
    /** Sinais de pedestre rastreados mas excluídos (distantes, laterais ou abaixo da faixa: outra travessia?). */
    val excludedPedestrianSignals: Int = 0,
    /** Estado explícito: a aplicabilidade do sinal à travessia do usuário é DESCONHECIDA sem fonte independente. */
    val signalApplicabilityUnknown: Boolean = true,
    /**
     * Faixa com evidência temporal FORTE (>= 4 detecções consistentes ao longo de >= 1,2 s). Uma detecção
     * isolada ou curta nunca muda o cenário da travessia.
     */
    val crosswalkStrong: Boolean = false,
    /** Algo parecido com faixa apareceu (mesmo sem confirmação): vale esperar um pouco antes de decidir o cenário. */
    val crosswalkCandidate: Boolean = false,
    /**
     * Sinal de PEDESTRE com associação considerada confiável para o fluxo simplificado: faixa forte, um único
     * sinal de pedestre na imagem (nenhum outro excluído), associado geometricamente à faixa, com a mesma cor
     * estável e rastreado há >= 1 s. Ainda é evidência visual (não há fonte independente); por isso o fluxo
     * de sinal verde nunca anuncia segurança e continua monitorando os veículos.
     */
    val signalReliable: Boolean = false)

/**
 * Rastreamento temporal leve dos semáforos (ID por proximidade/IoU, mesmo tipo pedestre/veículo).
 * Cor estável = última janela de 1 s com >= 3 observações iguais. Oclusão curta (<= [holdMs]) mantém o ID.
 */
class SignalTracker(private val holdMs: Long = 500, private val maxTracks: Int = 12) {
    class Track(val id: Int, val pedestrian: Boolean, var box: RawDetection, var lastSeenMs: Long) {
        val firstSeenMs: Long = lastSeenMs
        val colors = ArrayDeque<Pair<Long, String>>()
        val color: String get() = colors.lastOrNull()?.second ?: "unknown"
        fun stableColor(nowMs: Long): String? {
            val recent = colors.filter { nowMs - it.first <= 1000 }
            if (recent.size < 3 || recent.last().first - recent.first().first < 600) return null
            return recent.map { it.second }.distinct().singleOrNull()
        }
    }
    private val tracks = mutableListOf<Track>()
    private var nextId = 0
    val active: List<Track> get() = tracks

    fun update(lights: List<RawDetection>, nowMs: Long): List<Track> {
        tracks.removeAll { nowMs - it.lastSeenMs > holdMs }
        val used = mutableSetOf<Track>()
        for (det in lights.sortedByDescending { it.confidence }) {
            val pedestrian = det.className.startsWith("pedestrian-light-")
            val color = det.className.substringAfterLast('-')
            val match = tracks.filter { it.pedestrian == pedestrian && it !in used }
                .minByOrNull { kotlin.math.hypot(it.box.cx - det.cx, it.box.cy - det.cy) }
                ?.takeIf { kotlin.math.hypot(it.box.cx - det.cx, it.box.cy - det.cy) <= 1.5f * maxOf(it.box.bboxW, it.box.bboxH, 8f) }
            val track = match ?: Track(nextId++, pedestrian, det, nowMs).also { tracks += it }
            if (match != null && match.color != color) match.colors.clear() // mudança de cor reinicia a estabilidade
            track.box = det; track.lastSeenMs = nowMs
            track.colors.addLast(nowMs to color)
            while (track.colors.size > 20) track.colors.removeFirst()
            used += track
        }
        while (tracks.size > maxTracks) tracks.removeAt(tracks.indexOf(tracks.minByOrNull { it.lastSeenMs }))
        return used.toList()
    }
    fun reset() { tracks.clear(); nextId = 0 }
}

/** Só YOLO confirma a faixa; ausência de detecção nunca comprova ausência de faixa/sinal. */
class SceneEvidenceTracker {
    private var firstCrosswalk = -1L
    private var lastCrosswalk = -1L
    private var crosswalkBox: RawDetection? = null
    private var hits = 0
    private var greenSinceMs = -1L
    private var lastSampleMs = -1L
    private val signalTracker = SignalTracker()
    val observedGreenSinceMs: Long get() = greenSinceMs

    fun update(detections: List<RawDetection>, nowMs: Long, width: Int): SceneEvidence {
        if (nowMs <= lastSampleMs) {
            reset()
            return SceneEvidence(CrosswalkStatus.UNCERTAIN,null,SignalApplicability.UNCERTAIN,nowMs)
        }
        if (lastSampleMs >= 0 && nowMs - lastSampleMs > 850) reset()
        lastSampleMs = nowMs
        val crossing = detections.filter { it.className == "crosswalk" && it.confidence >= .20f }
            .maxByOrNull { it.confidence }
        val crosswalkCandidate = crossing != null || hits > 0
        if (crossing != null) {
            val previous = crosswalkBox
            val consistent = previous != null && overlap(previous,crossing) >= .25f
            if (lastCrosswalk < 0 || nowMs - lastCrosswalk !in 0..850 || !consistent) {
                firstCrosswalk = nowMs; hits = 0
            }
            if (nowMs > lastCrosswalk) hits++
            lastCrosswalk = nowMs
            crosswalkBox = crossing
        }
        if (nowMs - lastCrosswalk !in 0..850) { firstCrosswalk = -1L; crosswalkBox = null; hits = 0 }
        // Hold can retain a previously confirmed stripe, never mature two brief hits by a timer.
        val confirmed = firstCrosswalk >= 0 && hits >= 2 && lastCrosswalk - firstCrosswalk >= 400 && crosswalkBox != null
        val lights = detections.filter { it.className.startsWith("pedestrian-light-") || it.className.startsWith("traffic-light-") }
        val seenTracks = signalTracker.update(lights, nowMs)
        val signalCandidates = detections.filter { it.className.startsWith("pedestrian-light-") }
        // O tipo visual é necessário, mas a correspondência geométrica é só um indício.
        // Sinal muito pequeno (distante) não é candidato: pode pertencer a outra travessia.
        val associated = signalCandidates.filter { det ->
            confirmed && crosswalkBox != null &&
                kotlin.math.abs(det.cx - crosswalkBox!!.cx) <= width * .38f &&
                det.cy < crosswalkBox!!.cy && det.bboxH >= width * .012f
        }
        val excluded = signalCandidates.size - associated.size
        val associatedTrack = associated.singleOrNull()?.let { det -> seenTracks.firstOrNull { it.box === det } }
        val colorStable = associatedTrack != null && associatedTrack.stableColor(nowMs) == associatedTrack.color
        val strong = confirmed && hits >= 4 && lastCrosswalk - firstCrosswalk >= 1200
        val reliable = strong && associated.size == 1 && excluded == 0 && colorStable &&
            associatedTrack != null && nowMs - associatedTrack.firstSeenMs >= 1000
        val signal = when {
            associated.size > 1 -> SignalApplicability.UNCERTAIN
            associated.singleOrNull()?.className == "pedestrian-light-red" -> SignalApplicability.PEDESTRIAN_RED
            associated.singleOrNull()?.className == "pedestrian-light-green" -> SignalApplicability.PEDESTRIAN_GREEN
            signalCandidates.isNotEmpty() -> SignalApplicability.UNCERTAIN
            detections.any { it.className.startsWith("traffic-light-") } -> SignalApplicability.VEHICLE_ONLY
            else -> SignalApplicability.NOT_OBSERVED
        }
        if (signal == SignalApplicability.PEDESTRIAN_GREEN) {
            if (greenSinceMs < 0) greenSinceMs = nowMs
        } else greenSinceMs = -1L
        // Associação geométrica + cor estável continuam sendo INDÍCIOS: signalAssociationVerified fica false.
        return SceneEvidence(if (confirmed) CrosswalkStatus.CONFIRMED else CrosswalkStatus.UNCERTAIN,
            if (confirmed) crosswalkBox else null, signal, nowMs, greenSinceMs,
            signalAssociationVerified = false, signalColorStable = colorStable,
            excludedPedestrianSignals = excluded, signalApplicabilityUnknown = true,
            crosswalkStrong = strong, crosswalkCandidate = crosswalkCandidate || crossing != null || hits > 0,
            signalReliable = reliable)
    }

    private fun overlap(a: RawDetection, b: RawDetection): Float {
        val intersection = (minOf(a.x2,b.x2)-maxOf(a.x1,b.x1)).coerceAtLeast(0f) *
            (minOf(a.y2,b.y2)-maxOf(a.y1,b.y1)).coerceAtLeast(0f)
        return intersection / (a.area+b.area-intersection).coerceAtLeast(1f)
    }
    fun reset() { firstCrosswalk = -1; lastCrosswalk = -1; crosswalkBox = null; hits = 0; greenSinceMs = -1; lastSampleMs = -1
        signalTracker.reset() }
}
