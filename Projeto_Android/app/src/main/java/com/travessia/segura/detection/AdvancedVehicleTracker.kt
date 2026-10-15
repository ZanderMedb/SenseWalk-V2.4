package com.travessia.segura.detection

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tracker de veículos V2.4, inspirado na estratégia de duas etapas do ByteTrack.
 *
 * Principais diferenças em relação ao tracker antigo:
 * - associa primeiro detecções de confiança alta e depois tenta recuperar tracks
 *   existentes usando detecções de confiança mais baixa;
 * - previsão de posição com velocidade relativa do objeto;
 * - compensação da translação global estimada pelo optical flow;
 * - matching class-aware, IoU + distância do centro + similaridade de tamanho;
 * - confirmação temporal e ghosts curtos para reduzir pisca-pisca;
 * - estatísticas por frame para o benchmark.
 *
 * Não é a implementação oficial completa do ByteTrack. É uma versão leve, sem
 * dependências nativas extras, adequada ao protótipo Android atual.
 */
class AdvancedVehicleTracker(
    private val minIoU: Float = 0.035f,
    private var maxCenterDistancePct: Float = 0.14f,
    private val maxSizeRatio: Float = 2.8f,
    private val maxMisses: Int = 8,
    private var ghostFrames: Int = 4,
    private val bboxSmoothingAlpha: Float = 0.74f,
    private val highConfidenceThreshold: Float = 0.35f
) {
    data class TrackerStats(
        val activeTracks: Int = 0,
        val newTracks: Int = 0,
        val removedTracks: Int = 0,
        val highConfidenceMatches: Int = 0,
        val lowConfidenceRecoveries: Int = 0,
        val ghostCount: Int = 0,
        val confirmedVisible: Int = 0
    )

    private data class TrackState(
        var det: RawDetection,
        val trackId: Int,
        var misses: Int = 0,
        var hits: Int = 1,
        var confirmed: Boolean = false,
        var vxRelative: Float = 0f,
        var vyRelative: Float = 0f,
        var confidenceEma: Float = det.confidence
    )

    private data class IndexedDetection(val originalIndex: Int, val det: RawDetection)
    private data class Candidate(val trackId: Int, val detIndex: Int, val score: Float)

    private val tracks = mutableMapOf<Int, TrackState>()
    private var nextId = 0

    var lastStats: TrackerStats = TrackerStats()
        private set

    fun update(
        detections: List<RawDetection>,
        frameWidth: Int,
        frameHeight: Int,
        cameraDx: Float = 0f,
        cameraDy: Float = 0f
    ): List<TrackedDetection> {
        val fw = frameWidth.coerceAtLeast(1).toFloat()
        val fh = frameHeight.coerceAtLeast(1).toFloat()
        val diagonal = hypot(fw, fh).coerceAtLeast(1f)

        val allIndexed = detections.mapIndexed { index, det -> IndexedDetection(index, det) }
        val high = allIndexed.filter { it.det.confidence >= highConfidenceThreshold }
        val low = allIndexed.filter { it.det.confidence < highConfidenceThreshold }

        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Int>()
        var highMatches = 0
        var lowRecoveries = 0
        var newTracks = 0
        var removedTracks = 0

        // Etapa 1: detecções mais confiáveis.
        val firstMatches = matchStage(
            candidateTracks = tracks.keys.toList(),
            detections = high,
            diagonal = diagonal,
            cameraDx = cameraDx,
            cameraDy = cameraDy,
            distanceMultiplier = 1f
        )
        for ((trackId, indexed) in firstMatches) {
            val track = tracks[trackId] ?: continue
            updateMatchedTrack(track, indexed.det, cameraDx, cameraDy)
            matchedTracks.add(trackId)
            matchedDetections.add(indexed.originalIndex)
            highMatches++
        }

        // Etapa 2: uma detecção fraca pode recuperar um track já confirmado. Isso é a
        // ideia mais importante do ByteTrack para evitar que um objeto suma por um frame.
        val unmatchedTracks = tracks.values
            .filter { it.trackId !in matchedTracks }
            .map { it.trackId }
        val secondMatches = matchStage(
            candidateTracks = unmatchedTracks,
            detections = low.filter { it.originalIndex !in matchedDetections },
            diagonal = diagonal,
            cameraDx = cameraDx,
            cameraDy = cameraDy,
            distanceMultiplier = 1.22f
        )
        for ((trackId, indexed) in secondMatches) {
            val track = tracks[trackId] ?: continue
            updateMatchedTrack(track, indexed.det, cameraDx, cameraDy)
            matchedTracks.add(trackId)
            matchedDetections.add(indexed.originalIndex)
            lowRecoveries++
        }

        // Detecções não associadas podem iniciar novos tracks. As fracas precisam
        // persistir por mais frames antes de serem confirmadas.
        for (indexed in allIndexed) {
            if (indexed.originalIndex in matchedDetections) continue
            val track = TrackState(det = indexed.det, trackId = nextId++)
            track.confirmed = shouldConfirm(track)
            tracks[track.trackId] = track
            matchedTracks.add(track.trackId) // observado no frame atual; não deve virar miss imediatamente
            newTracks++
        }

        // Avança tracks perdidos usando movimento da câmera + velocidade relativa.
        val toRemove = mutableListOf<Int>()
        for (track in tracks.values) {
            if (track.trackId in matchedTracks) continue
            track.misses++
            track.det = predictNextObserved(track, cameraDx, cameraDy)
            track.confidenceEma *= 0.86f
            if (track.misses > maxMisses) toRemove.add(track.trackId)
        }
        for (id in toRemove) {
            tracks.remove(id)
            removedTracks++
        }

        val result = mutableListOf<TrackedDetection>()
        var ghosts = 0
        var visible = 0
        for (track in tracks.values.sortedBy { it.trackId }) {
            if (!track.confirmed) continue
            if (track.misses == 0) {
                visible++
                result.add(track.toTracked(isGhost = false))
            } else if (track.misses <= ghostFrames) {
                ghosts++
                result.add(track.toTracked(isGhost = true))
            }
        }

        lastStats = TrackerStats(
            activeTracks = tracks.values.count { it.confirmed },
            newTracks = newTracks,
            removedTracks = removedTracks,
            highConfidenceMatches = highMatches,
            lowConfidenceRecoveries = lowRecoveries,
            ghostCount = ghosts,
            confirmedVisible = visible
        )
        return result
    }

    fun configure(centerDistancePct: Float, ghostFrameCount: Int) {
        maxCenterDistancePct = centerDistancePct.coerceIn(0.05f, 0.35f)
        ghostFrames = ghostFrameCount.coerceIn(0, maxMisses)
    }

    fun reset() {
        tracks.clear()
        nextId = 0
        lastStats = TrackerStats()
    }

    private fun matchStage(
        candidateTracks: List<Int>,
        detections: List<IndexedDetection>,
        diagonal: Float,
        cameraDx: Float,
        cameraDy: Float,
        distanceMultiplier: Float
    ): Map<Int, IndexedDetection> {
        if (candidateTracks.isEmpty() || detections.isEmpty()) return emptyMap()

        val candidates = mutableListOf<Candidate>()
        val byOriginalIndex = detections.associateBy { it.originalIndex }

        for (trackId in candidateTracks) {
            val track = tracks[trackId] ?: continue
            val predicted = predictForMatching(track, cameraDx, cameraDy)
            for (indexed in detections) {
                val det = indexed.det
                val sameClass = track.det.classId == det.classId
                val iou = computeIoU(predicted, det)
                val centerDistance = hypot(predicted.cx - det.cx, predicted.cy - det.cy) / diagonal
                val sizeRatio = sizeRatio(predicted, det)
                val maxDistance = maxCenterDistancePct * distanceMultiplier

                val geometryOk = (iou >= minIoU || centerDistance <= maxDistance) && sizeRatio <= maxSizeRatio
                if (!geometryOk) continue

                // Troca de classe só é aceita quando a geometria é bastante coerente.
                if (!sameClass && iou < 0.17f && centerDistance > 0.075f * distanceMultiplier) continue

                val centerScore = (1f - centerDistance / maxDistance).coerceIn(0f, 1f)
                val sizeScore = (1f / sizeRatio).coerceIn(0f, 1f)
                val classScore = if (sameClass) 1f else 0.22f
                val confidenceScore = det.confidence.coerceIn(0f, 1f)
                val score = 0.43f * iou +
                        0.31f * centerScore +
                        0.11f * sizeScore +
                        0.08f * classScore +
                        0.07f * confidenceScore
                candidates.add(Candidate(trackId, indexed.originalIndex, score))
            }
        }

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

    private fun updateMatchedTrack(
        track: TrackState,
        incoming: RawDetection,
        cameraDx: Float,
        cameraDy: Float
    ) {
        val previousObserved = track.det
        val cameraShiftedPrevious = previousObserved.shift(cameraDx, cameraDy)

        // Remove o movimento global da câmera antes de aprender a velocidade do objeto.
        val relativeDx = incoming.cx - cameraShiftedPrevious.cx
        val relativeDy = incoming.cy - cameraShiftedPrevious.cy
        track.vxRelative = 0.58f * track.vxRelative + 0.42f * relativeDx
        track.vyRelative = 0.58f * track.vyRelative + 0.42f * relativeDy

        val a = bboxSmoothingAlpha
        track.det = RawDetection(
            x1 = a * incoming.x1 + (1f - a) * cameraShiftedPrevious.x1,
            y1 = a * incoming.y1 + (1f - a) * cameraShiftedPrevious.y1,
            x2 = a * incoming.x2 + (1f - a) * cameraShiftedPrevious.x2,
            y2 = a * incoming.y2 + (1f - a) * cameraShiftedPrevious.y2,
            classId = incoming.classId,
            className = incoming.className,
            confidence = incoming.confidence
        )
        track.misses = 0
        track.hits++
        track.confidenceEma = 0.66f * incoming.confidence + 0.34f * track.confidenceEma
        if (!track.confirmed) track.confirmed = shouldConfirm(track)
    }

    private fun predictForMatching(track: TrackState, cameraDx: Float, cameraDy: Float): RawDetection {
        val damping = 0.88f.pow(track.misses.coerceAtLeast(0))
        return track.det.shift(
            cameraDx + track.vxRelative * damping,
            cameraDy + track.vyRelative * damping
        )
    }

    private fun predictNextObserved(track: TrackState, cameraDx: Float, cameraDy: Float): RawDetection {
        val damping = 0.86f.pow(track.misses.coerceAtLeast(1) - 1)
        return track.det.shift(
            cameraDx + track.vxRelative * damping,
            cameraDy + track.vyRelative * damping
        )
    }

    private fun shouldConfirm(track: TrackState): Boolean {
        return when {
            track.confidenceEma >= 0.60f -> track.hits >= 1
            track.confidenceEma >= 0.32f -> track.hits >= 2
            else -> track.hits >= 3
        }
    }

    private fun TrackState.toTracked(isGhost: Boolean): TrackedDetection {
        val conf = if (isGhost) {
            (confidenceEma * 0.82f.pow(misses.coerceAtLeast(1))).coerceAtLeast(0.05f)
        } else confidenceEma
        return TrackedDetection(
            trackId = trackId,
            x1 = det.x1,
            y1 = det.y1,
            x2 = det.x2,
            y2 = det.y2,
            classId = det.classId,
            className = det.className,
            confidence = conf,
            isGhost = isGhost,
            hits = hits
        )
    }

    private fun RawDetection.shift(dx: Float, dy: Float): RawDetection = copy(
        x1 = x1 + dx,
        y1 = y1 + dy,
        x2 = x2 + dx,
        y2 = y2 + dy
    )

    private fun sizeRatio(a: RawDetection, b: RawDetection): Float {
        val aw = a.bboxW.coerceAtLeast(1f)
        val ah = a.bboxH.coerceAtLeast(1f)
        val bw = b.bboxW.coerceAtLeast(1f)
        val bh = b.bboxH.coerceAtLeast(1f)
        val wr = max(aw / bw, bw / aw)
        val hr = max(ah / bh, bh / ah)
        return max(wr, hr)
    }

    private fun computeIoU(a: RawDetection, b: RawDetection): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        val areaA = a.bboxW.coerceAtLeast(0f) * a.bboxH.coerceAtLeast(0f)
        val areaB = b.bboxW.coerceAtLeast(0f) * b.bboxH.coerceAtLeast(0f)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }
}
