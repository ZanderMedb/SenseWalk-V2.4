package com.travessia.segura.tracking

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * Memória de curto prazo, SOMENTE na RAM, dos veículos de uma sessão de análise (direita → esquerda → frente).
 *
 * - Não é um segundo tracker: recebe os IDs do [com.travessia.segura.detection.AdvancedVehicleTracker] e as
 *   classificações brutas do [Rastreador], e devolve um estado ESTABILIZADO.
 * - Estabilização: um estado novo só substitui o anterior após 3 observações reais, temporalmente distintas e
 *   compatíveis (histerese). Movimento relevante ou possível conflito trocam o estado IMEDIATAMENTE (alertas não
 *   esperam). Câmera girando/andando invalida "aparentemente parado" na hora.
 * - Histórico por direção: registros de uma direção permanecem ao observar outra; a validade decai com o tempo.
 *   Ao voltar à mesma direção, um track novo pode ser vinculado ao registro anterior apenas com geometria
 *   inequívoca; o estado precisa ser reconfirmado (3 observações). Nunca justifica conclusão favorável.
 * - Limites: [maxRecords] registros, expiração após [retentionMs] sem observação real.
 */
class CrossingSessionMemory(
    private val confirmObservations: Int = 3,
    private val minObservationGapMs: Long = 50,
    private val freshMs: Long = 1500,
    private val validityTauMs: Long = 5000,
    private val retentionMs: Long = 30_000,
    private val maxRecords: Int = 64,
    private val maxPositions: Int = 12
) {
    enum class Direction { RIGHT, LEFT, FRONT }

    class VehicleRecord(val memoryId: Int, var trackId: Int, val sessionId: Long, var className: String,
        var bbox: FloatArray, val direction: Direction?, var lastSeenMs: Long) {
        /** Estado estabilizado (o que o resto do app usa). */
        var state: String = MotionClass.MOVIMENTO_INCERTO; internal set
        var confidence: Float = 0f; internal set
        var possibleConflict: Boolean = false; internal set
        /** Já teve conflito nesta sessão (conservador ao revisitar a direção). */
        var conflictSeen: Boolean = false; internal set
        var relevance: Float = 1f; internal set
        val positions = ArrayDeque<FloatArray>() // [t, cx, cy, w]
        internal var candidate: String? = null
        internal var candidateCount = 0
        internal var lastCountedMs = -1L
        var transitions = 0; internal set
        /** Instante da última mudança do estado estabilizado (diagnóstico). */
        var lastTransitionMs = -1L; internal set
        /** Herdado de uma visita anterior à mesma direção: precisa ser reconfirmado. */
        var revisited = false; internal set
    }

    private val records = LinkedHashMap<Int, VehicleRecord>()
    private val byTrack = HashMap<Int, Int>()
    private var nextMemoryId = 0
    var sessionId = -1L; private set
    val size: Int get() = records.size
    fun records(): List<VehicleRecord> = records.values.toList()
    fun recordFor(trackId: Int): VehicleRecord? = byTrack[trackId]?.let { records[it] }

    /** Nova sessão de análise: histórico anterior descartado. */
    @Synchronized fun beginSession(id: Long) { clear(); sessionId = id }
    /** "Parar", troca de câmera ou reinício do tracker (IDs recomeçam): limpar tudo. */
    @Synchronized fun clear() { records.clear(); byTrack.clear(); nextMemoryId = 0 }

    /**
     * Registra uma observação REAL (nunca ghost) e devolve o estado estabilizado.
     * @param raw classificação bruta do Rastreador; @param cameraMotionInvalid câmera girando/andando/não compensada.
     */
    @Synchronized fun observe(trackId: Int, className: String, bbox: FloatArray, raw: String, rawConfidence: Float,
        nowMs: Long, direction: Direction?, frameW: Int, frameH: Int, cameraMotionInvalid: Boolean): VehicleRecord {
        prune(nowMs)
        val record = recordFor(trackId) ?: link(trackId, className, bbox, direction, nowMs) ?: create(trackId, className, bbox, direction, nowMs)
        record.className = className; record.bbox = bbox; record.lastSeenMs = nowMs
        val cx = (bbox[0] + bbox[2]) / 2f; val cy = (bbox[1] + bbox[3]) / 2f; val w = bbox[2] - bbox[0]
        record.positions.addLast(floatArrayOf(nowMs.toFloat(), cx, cy, w))
        while (record.positions.size > maxPositions) record.positions.removeFirst()
        // Objetos pequenos/distantes: confiança proporcional à evidência (não ignorados).
        val sizeFactor = (w / (frameW.coerceAtLeast(1) * .06f)).coerceIn(.3f, 1f)
        // Relevância operacional menor só quando a geometria indica fora da área de conflito (alto e pequeno).
        record.relevance = if (bbox[3] < frameH * .45f && w < frameW * .06f) .5f else 1f
        stabilize(record, raw, nowMs, cameraMotionInvalid)
        record.confidence = (rawConfidence * sizeFactor).coerceIn(0f, 1f)
        record.possibleConflict = record.state in MotionClass.CONFLICT
        if (record.possibleConflict) record.conflictSeen = true
        return record
    }

    private fun stabilize(r: VehicleRecord, raw: String, nowMs: Long, cameraMotionInvalid: Boolean) {
        fun set(state: String) { if (r.state != state) { r.state = state; r.transitions++; r.lastTransitionMs = nowMs }; r.candidate = null; r.candidateCount = 0 }
        // Escalada imediata: possível conflito / movimento relevante (alertas não esperam 3 frames).
        // Descer ou alternar entre classes de movimento (vermelho <-> amarelo) exige 3 observações: sem pisca-pisca.
        if (raw in IMMEDIATE && severity(raw) > severity(r.state)) {
            // Pequeno e fora da área de conflito: 2 observações seguidas (~0,6 s) antes de alertar; perto, imediato.
            if (r.relevance >= 1f) { set(raw); return }
            if (r.lastCountedMs >= 0 && nowMs - r.lastCountedMs < minObservationGapMs) return
            r.lastCountedMs = nowMs
            if (r.candidate == raw) r.candidateCount++ else { r.candidate = raw; r.candidateCount = 1 }
            if (r.candidateCount >= 2) set(raw)
            return
        }
        // Câmera não compensada: "aparentemente parado" deixa de valer na hora.
        if (cameraMotionInvalid && r.state == MotionClass.APARENTEMENTE_PARADO) { set(MotionClass.MOVIMENTO_INCERTO); return }
        val target = if (raw == MotionClass.PARADO) MotionClass.APARENTEMENTE_PARADO else raw
        if (target == r.state) { r.candidate = null; r.candidateCount = 0; return }
        // Só observações temporalmente distintas contam para a confirmação.
        if (r.lastCountedMs >= 0 && nowMs - r.lastCountedMs < minObservationGapMs) return
        r.lastCountedMs = nowMs
        if (target == MotionClass.APARENTEMENTE_PARADO && cameraMotionInvalid) { r.candidate = null; r.candidateCount = 0; return }
        if (r.candidate == target) r.candidateCount++ else { r.candidate = target; r.candidateCount = 1 }
        if (r.candidateCount >= confirmObservations) set(target)
    }

    /**
     * Revisita da mesma direção: vincula um track novo a um registro antigo só se houver UM candidato inequívoco
     * (mesma direção, mesmo grupo, centro a <= 0,6 largura, tamanho parecido, sem track ativo próprio).
     * O estado herdado é rebaixado para incerto até ser reconfirmado.
     */
    private fun link(trackId: Int, className: String, bbox: FloatArray, direction: Direction?, nowMs: Long): VehicleRecord? {
        if (direction == null) return null
        val cx = (bbox[0] + bbox[2]) / 2f; val cy = (bbox[1] + bbox[3]) / 2f; val w = max(1f, bbox[2] - bbox[0])
        val candidates = records.values.filter { r ->
            r.direction == direction && nowMs - r.lastSeenMs > freshMs && byTrack[r.trackId] == r.memoryId &&
                sameGroup(r.className, className) &&
                hypot((r.bbox[0] + r.bbox[2]) / 2f - cx, (r.bbox[1] + r.bbox[3]) / 2f - cy) <= .6f * w &&
                max(w, r.bbox[2] - r.bbox[0]) / maxOf(1f, minOf(w, r.bbox[2] - r.bbox[0])) <= 1.5f
        }
        if (candidates.size != 1) return null // ambíguo ou inexistente: não associar
        val r = candidates.single()
        byTrack.remove(r.trackId)
        r.trackId = trackId; byTrack[trackId] = r.memoryId
        r.revisited = true
        if (r.state == MotionClass.APARENTEMENTE_PARADO || r.state == MotionClass.PARADO) {
            r.state = MotionClass.MOVIMENTO_INCERTO; r.transitions++ // antigo "parado" não continua valendo
        }
        r.candidate = null; r.candidateCount = 0; r.lastCountedMs = -1
        return r
    }

    private fun create(trackId: Int, className: String, bbox: FloatArray, direction: Direction?, nowMs: Long): VehicleRecord {
        val r = VehicleRecord(nextMemoryId++, trackId, sessionId, className, bbox, direction, nowMs)
        records[r.memoryId] = r; byTrack[trackId] = r.memoryId
        return r
    }

    private fun prune(nowMs: Long) {
        val expired = records.values.filter { nowMs - it.lastSeenMs > retentionMs }
        for (r in expired) { records.remove(r.memoryId); byTrack.remove(r.trackId) }
        while (records.size >= maxRecords) {
            val oldest = records.values.minByOrNull { it.lastSeenMs } ?: break
            records.remove(oldest.memoryId); byTrack.remove(oldest.trackId)
        }
    }

    /** Validade do registro: 1 quando visto agora, decai com o tempo sem observação real. */
    fun validity(r: VehicleRecord, nowMs: Long): Float =
        exp(-(nowMs - r.lastSeenMs).coerceAtLeast(0L).toDouble() / validityTauMs).toFloat()

    /** Histórico da sessão: nunca autoriza conclusão — só registros FRESCOS refletem a cena atual. */
    fun isFresh(r: VehicleRecord, nowMs: Long) = nowMs - r.lastSeenMs <= freshMs

    /** Registros de uma direção (para revisão/diagnóstico), com validade decrescente. */
    @Synchronized fun historyFor(direction: Direction, nowMs: Long): List<Pair<VehicleRecord, Float>> =
        records.values.filter { it.direction == direction }.map { it to validity(it, nowMs) }

    /** Gravidade para a histerese: conflito > movimento lateral > demais. */
    private fun severity(state: String) = when (state) {
        MotionClass.APROXIMANDO, MotionClass.POTENCIAL_CONFLITO, MotionClass.POSSIVEL_APROXIMACAO -> 2
        MotionClass.EM_MOVIMENTO -> 1
        else -> 0
    }

    private fun sameGroup(a: String, b: String) = a == b || (a in VEHICLES && b in VEHICLES)

    companion object {
        private val VEHICLES = setOf("car", "truck", "bus", "motorcycle", "bicycle")
        /** Movimento relevante / possível conflito: troca imediata. */
        val IMMEDIATE = setOf(MotionClass.APROXIMANDO, MotionClass.POTENCIAL_CONFLITO, MotionClass.POSSIVEL_APROXIMACAO,
            MotionClass.EM_MOVIMENTO)
    }
}
