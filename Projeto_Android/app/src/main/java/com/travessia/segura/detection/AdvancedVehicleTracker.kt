package com.travessia.segura.detection

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tracker de veículos V2.4 (duas etapas, inspirado no ByteTrack), leve e sem dependências nativas.
 *
 * Associação por custo, com previsão cinemática dependente do intervalo REAL entre frames:
 * - a última caixa OBSERVADA fica separada da posição apenas PREVISTA. Ghosts são previsões, nunca
 *   observações; não alimentam velocidade nem histórico de movimento;
 * - previsão = observado + movimento global acumulado da câmera + velocidade relativa x tempo desde a
 *   última observação. Assim um carro rápido continua associável mesmo após 1 a 3 frames perdidos;
 * - a janela de associação cresce com a velocidade estimada, com o tamanho da caixa e com o tempo sem
 *   observação, limitada para não "puxar" veículos distantes;
 * - custo combina IoU, distância normalizada pela janela, tamanho, classe (grupo veículo tolerado) e
 *   coerência de direção, o que evita trocar IDs quando duas trajetórias se cruzam;
 * - detecção não associada que se sobrepõe fortemente a um track já associado (ex.: car/truck no
 *   mesmo objeto) não cria ID novo;
 * - persistência sem observação limitada no tempo ([maxUnobservedMs]) e número máximo de tracks.
 */
class AdvancedVehicleTracker(
    private val minIoU: Float = 0.035f,
    private var maxCenterDistancePct: Float = 0.14f,
    private val maxSizeRatio: Float = 2.8f,
    private val maxMisses: Int = 8,
    private var ghostFrames: Int = 4,
    private val bboxSmoothingAlpha: Float = 0.74f,
    private val highConfidenceThreshold: Float = 0.35f,
    private val maxUnobservedMs: Long = 1500,
    private val maxTracks: Int = 48,
    private val maxGatePct: Float = 0.38f
) {
    data class TrackerStats(
        val activeTracks: Int = 0,
        val newTracks: Int = 0,
        val removedTracks: Int = 0,
        val highConfidenceMatches: Int = 0,
        val lowConfidenceRecoveries: Int = 0,
        val ghostCount: Int = 0,
        val confirmedVisible: Int = 0,
        val unobservedTracks: Int = 0,
        /** Tracks re-associados depois de 1+ frames sem observação. */
        val recoveredAfterMiss: Int = 0,
        /** Detecções duplicadas (mesmo objeto) que não geraram ID novo. */
        val duplicatesSuppressed: Int = 0
    )

    private data class TrackState(
        /** Última caixa observada (suavizada), no referencial da imagem do instante [lastSeenMs]. */
        var observed: RawDetection,
        val trackId: Int,
        var misses: Int = 0,
        var hits: Int = 1,
        var confirmed: Boolean = false,
        /** Velocidade relativa ao fundo (px/s), aprendida só com observações reais. */
        var vx: Float = 0f,
        var vy: Float = 0f,
        var velocitySamples: Int = 0,
        var confidenceEma: Float = observed.confidence,
        var lastSeenMs: Long = 0L,
        /** Translação global da câmera acumulada desde a última observação. */
        var cameraSinceSeenX: Float = 0f,
        var cameraSinceSeenY: Float = 0f
    )

    private data class IndexedDetection(val originalIndex: Int, val det: RawDetection)
    private data class Candidate(val trackId: Int, val detIndex: Int, val score: Float)

    private val tracks = mutableMapOf<Int, TrackState>()
    private var nextId = 0
    private var lastFrameMs = -1L
    private var frameMs = 0L

    var lastStats: TrackerStats = TrackerStats()
        private set

    fun update(
        detections: List<RawDetection>,
        frameWidth: Int,
        frameHeight: Int,
        cameraDx: Float = 0f,
        cameraDy: Float = 0f,
        timestampMs: Long = System.nanoTime() / 1_000_000L
    ): List<TrackedDetection> {
        if (timestampMs <= lastFrameMs) return emptyList()
        lastFrameMs = timestampMs
        frameMs = timestampMs
        val fw = frameWidth.coerceAtLeast(1).toFloat()
        val fh = frameHeight.coerceAtLeast(1).toFloat()
        val diagonal = hypot(fw, fh).coerceAtLeast(1f)

        // Movimento global da câmera deste frame entra em todas as previsões.
        for (t in tracks.values) { t.cameraSinceSeenX += cameraDx; t.cameraSinceSeenY += cameraDy }

        // Expiração por tempo real sem observação, independente do FPS.
        val before = tracks.size
        tracks.entries.removeAll { timestampMs - it.value.lastSeenMs > maxUnobservedMs }
        var removedTracks = before - tracks.size

        val allIndexed = detections.mapIndexed { index, det -> IndexedDetection(index, det) }
        val high = allIndexed.filter { it.det.confidence >= highConfidenceThreshold }
        val low = allIndexed.filter { it.det.confidence < highConfidenceThreshold }

        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Int>()
        var highMatches = 0
        var lowRecoveries = 0
        var recovered = 0

        fun apply(matches: Map<Int, IndexedDetection>, low: Boolean) {
            for ((trackId, indexed) in matches) {
                val track = tracks[trackId] ?: continue
                if (track.misses > 0) recovered++
                updateMatchedTrack(track, indexed.det)
                matchedTracks.add(trackId)
                matchedDetections.add(indexed.originalIndex)
                if (low) lowRecoveries++ else highMatches++
            }
        }

        // Etapa 1: detecções confiáveis contra todos os tracks.
        apply(matchStage(tracks.keys.toList(), high, diagonal, 1f), low = false)
        // Etapa 2: detecções fracas só recuperam tracks existentes (oscilação de confiança).
        apply(matchStage(tracks.keys.filter { it !in matchedTracks },
            low.filter { it.originalIndex !in matchedDetections }, diagonal, 1.2f), low = true)

        // Novos tracks, exceto duplicatas fortes de um objeto já associado neste frame.
        var newTracks = 0
        var duplicates = 0
        for (indexed in allIndexed) {
            if (indexed.originalIndex in matchedDetections) continue
            val duplicate = matchedTracks.any { id -> tracks[id]?.let { computeIoU(it.observed, indexed.det) >= .55f } == true }
            if (duplicate) { duplicates++; continue }
            val track = TrackState(observed = indexed.det, trackId = nextId++, lastSeenMs = timestampMs)
            track.confirmed = shouldConfirm(track)
            tracks[track.trackId] = track
            matchedTracks.add(track.trackId)
            newTracks++
        }

        for (track in tracks.values) if (track.trackId !in matchedTracks) {
            track.misses++
            track.confidenceEma *= 0.86f
        }
        if (tracks.size > maxTracks) {
            // Estrutura limitada: descartar primeiro os tracks sem observação há mais tempo.
            val excess = tracks.values.sortedWith(compareBy<TrackState> { it.misses == 0 }.thenBy { it.lastSeenMs })
                .take(tracks.size - maxTracks)
            for (t in excess) { tracks.remove(t.trackId); removedTracks++ }
        }

        val result = mutableListOf<TrackedDetection>()
        var ghosts = 0
        var visible = 0
        for (track in tracks.values.sortedBy { it.trackId }) {
            if (track.misses == 0) {
                visible++
                result.add(track.toTracked(track.observed, isGhost = false))
            } else if (track.misses <= ghostFrames) {
                // Ghost = posição prevista, marcada como não observada.
                ghosts++
                result.add(track.toTracked(predict(track, timestampMs), isGhost = true))
            }
        }

        lastStats = TrackerStats(
            activeTracks = tracks.values.count { it.confirmed },
            newTracks = newTracks,
            removedTracks = removedTracks,
            highConfidenceMatches = highMatches,
            lowConfidenceRecoveries = lowRecoveries,
            ghostCount = ghosts,
            confirmedVisible = visible,
            unobservedTracks = tracks.values.count { it.misses > 0 },
            recoveredAfterMiss = recovered,
            duplicatesSuppressed = duplicates
        )
        return result
    }

    fun configure(centerDistancePct: Float, ghostFrameCount: Int) {
        maxCenterDistancePct = centerDistancePct.coerceIn(0.05f, 0.35f)
        ghostFrames = ghostFrameCount.coerceIn(0, maxMisses)
    }

    /** Troca de câmera/nova sessão: IDs e históricos não atravessam sessões independentes. */
    fun reset() {
        tracks.clear()
        nextId = 0
        lastFrameMs = -1L
        lastStats = TrackerStats()
    }

    val trackCount: Int get() = tracks.size

    private fun predict(track: TrackState, nowMs: Long): RawDetection {
        val dt = ((nowMs - track.lastSeenMs) / 1000f).coerceIn(0f, 1.5f)
        // Velocidade só depois de duas observações; amortecida em perdas longas.
        val damping = if (track.velocitySamples == 0) 0f else 0.9f.pow((track.misses - 1).coerceAtLeast(0))
        return track.observed.shift(track.cameraSinceSeenX + track.vx * dt * damping,
            track.cameraSinceSeenY + track.vy * dt * damping)
    }

    /** Janela de associação: base + tamanho do objeto + deslocamento esperado pela velocidade e tempo. */
    private fun gate(track: TrackState, dtSeconds: Float, diagonal: Float, multiplier: Float): Float {
        val size = max(track.observed.bboxW, track.observed.bboxH)
        val speed = hypot(track.vx, track.vy)
        val base = max(maxCenterDistancePct * diagonal, .55f * size)
        val uncertainty = .5f * speed * dtSeconds + .04f * diagonal * track.misses
        return min((base + uncertainty) * multiplier, maxGatePct * diagonal)
    }

    private fun matchStage(
        candidateTracks: List<Int>,
        detections: List<IndexedDetection>,
        diagonal: Float,
        multiplier: Float
    ): Map<Int, IndexedDetection> {
        if (candidateTracks.isEmpty() || detections.isEmpty()) return emptyMap()
        val candidates = mutableListOf<Candidate>()
        val byOriginalIndex = detections.associateBy { it.originalIndex }

        for (trackId in candidateTracks) {
            val track = tracks[trackId] ?: continue
            val dt = ((frameMs - track.lastSeenMs) / 1000f).coerceIn(.001f, 1.5f)
            val predicted = predict(track, frameMs)
            val maxDistance = gate(track, dt, diagonal, multiplier)
            for (indexed in detections) {
                val det = indexed.det
                val sameClass = track.observed.classId == det.classId
                val sameGroup = sameClass || (isVehicle(track.observed.classId) && isVehicle(det.classId))
                if (!sameGroup) continue // semáforo/faixa nunca herdam ID de veículo
                val iou = computeIoU(predicted, det)
                val distance = hypot(predicted.cx - det.cx, predicted.cy - det.cy)
                val ratio = sizeRatio(predicted, det)
                // Sem velocidade aprendida (2º frame do track): um veículo rápido pode saltar ~2 larguras
                // na horizontal. Janela estendida só para mesma classe, tamanho parecido e mesma faixa vertical.
                val lateralJump = track.velocitySamples == 0 && sameClass && ratio <= 1.6f &&
                    abs(det.cy - predicted.cy) <= .6f * predicted.bboxH &&
                    abs(det.cx - predicted.cx) <= min(2.2f * predicted.bboxW, maxGatePct * diagonal)
                if (!((iou >= minIoU || distance <= maxDistance || lateralJump) && ratio <= maxSizeRatio)) continue
                // Troca car/truck/bus só com geometria bem coerente.
                if (!sameClass && iou < .17f && distance > .5f * maxDistance) continue

                // Coerência de direção: deslocamento oposto à velocidade aprendida é penalizado.
                val speed = hypot(track.vx, track.vy)
                val direction = if (track.velocitySamples >= 2 && speed > .05f * diagonal) {
                    val obsX = det.cx - (track.observed.cx + track.cameraSinceSeenX)
                    val obsY = det.cy - (track.observed.cy + track.cameraSinceSeenY)
                    val norm = hypot(obsX, obsY)
                    if (norm < 1f) 1f else ((obsX * track.vx + obsY * track.vy) / (norm * speed) + 1f) / 2f
                } else 1f

                val centerScore = (1f - distance / max(maxDistance, if (lateralJump) 2.2f * predicted.bboxW else 0f)).coerceIn(0f, 1f)
                val sizeScore = (1f / ratio).coerceIn(0f, 1f)
                val classScore = if (sameClass) 1f else .5f
                val score = .38f * iou + .30f * centerScore + .10f * sizeScore + .08f * classScore +
                    .06f * det.confidence.coerceIn(0f, 1f) + .08f * direction
                candidates.add(Candidate(trackId, indexed.originalIndex, score))
            }
        }

        // Atribuição gulosa pelo maior custo-benefício (n pequeno; equivalente prático ao húngaro).
        candidates.sortByDescending { it.score }
        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()
        val result = linkedMapOf<Int, IndexedDetection>()
        for (candidate in candidates) {
            if (candidate.trackId in usedTracks || candidate.detIndex in usedDetections) continue
            val indexed = byOriginalIndex[candidate.detIndex] ?: continue
            usedTracks.add(candidate.trackId)
            usedDetections.add(candidate.detIndex)
            result[candidate.trackId] = indexed
        }
        return result
    }

    private fun updateMatchedTrack(track: TrackState, incoming: RawDetection) {
        val dt = ((frameMs - track.lastSeenMs) / 1000f).coerceIn(.001f, 1.5f)
        // Referência = última OBSERVAÇÃO deslocada pela câmera (nunca uma posição prevista).
        val reference = track.observed.shift(track.cameraSinceSeenX, track.cameraSinceSeenY)
        val relX = (incoming.cx - reference.cx) / dt
        val relY = (incoming.cy - reference.cy) / dt
        if (track.velocitySamples == 0) { track.vx = relX; track.vy = relY }
        else {
            // Após uma perda, a velocidade da lacuna é menos confiável: peso menor, mas não zerada.
            val w = if (track.misses > 0) .3f else .42f
            track.vx = (1 - w) * track.vx + w * relX
            track.vy = (1 - w) * track.vy + w * relY
        }
        track.velocitySamples++

        // Após perda, não suavizar com a posição antiga: a nova observação manda.
        val a = if (track.misses > 0) 1f else bboxSmoothingAlpha
        track.observed = RawDetection(
            x1 = a * incoming.x1 + (1f - a) * reference.x1,
            y1 = a * incoming.y1 + (1f - a) * reference.y1,
            x2 = a * incoming.x2 + (1f - a) * reference.x2,
            y2 = a * incoming.y2 + (1f - a) * reference.y2,
            classId = incoming.classId,
            className = incoming.className,
            confidence = incoming.confidence
        )
        track.cameraSinceSeenX = 0f; track.cameraSinceSeenY = 0f
        track.misses = 0
        track.lastSeenMs = frameMs
        track.hits++
        track.confidenceEma = 0.66f * incoming.confidence + 0.34f * track.confidenceEma
        if (!track.confirmed) track.confirmed = shouldConfirm(track)
    }

    private fun shouldConfirm(track: TrackState): Boolean = when {
        track.confidenceEma >= 0.60f -> track.hits >= 1
        track.confidenceEma >= 0.32f -> track.hits >= 2
        else -> track.hits >= 3
    }

    private fun TrackState.toTracked(box: RawDetection, isGhost: Boolean): TrackedDetection {
        val conf = if (isGhost) (confidenceEma * 0.82f.pow(misses.coerceAtLeast(1))).coerceAtLeast(0.05f)
            else confidenceEma
        return TrackedDetection(trackId = trackId, x1 = box.x1, y1 = box.y1, x2 = box.x2, y2 = box.y2,
            classId = box.classId, className = box.className, confidence = conf, isGhost = isGhost, hits = hits)
    }

    private fun isVehicle(classId: Int) = classId in 0..4

    private fun RawDetection.shift(dx: Float, dy: Float): RawDetection =
        copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)

    private fun sizeRatio(a: RawDetection, b: RawDetection): Float {
        val wr = max(a.bboxW.coerceAtLeast(1f) / b.bboxW.coerceAtLeast(1f), b.bboxW.coerceAtLeast(1f) / a.bboxW.coerceAtLeast(1f))
        val hr = max(a.bboxH.coerceAtLeast(1f) / b.bboxH.coerceAtLeast(1f), b.bboxH.coerceAtLeast(1f) / a.bboxH.coerceAtLeast(1f))
        return max(wr, hr)
    }

    private fun computeIoU(a: RawDetection, b: RawDetection): Float {
        val inter = (min(a.x2, b.x2) - max(a.x1, b.x1)).coerceAtLeast(0f) * (min(a.y2, b.y2) - max(a.y1, b.y1)).coerceAtLeast(0f)
        val union = a.bboxW.coerceAtLeast(0f) * a.bboxH.coerceAtLeast(0f) + b.bboxW.coerceAtLeast(0f) * b.bboxH.coerceAtLeast(0f) - inter
        return if (union > 0f) inter / union else 0f
    }
}
