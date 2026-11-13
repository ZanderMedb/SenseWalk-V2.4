package com.travessia.segura.benchmark

/**
 * Métricas leves de fluidez (sem arquivo, sem alocação por amostra além do anel).
 * Tempos em ms: média, p50 e p95 por janela; contadores viram taxa por segundo.
 * Relatório no logcat (tag "SenseWalkPerf") a cada [reportEveryMs]:
 *   adb logcat -s SenseWalkPerf
 *
 * Métricas usadas:
 *  camera_frames/s   frames entregues pelo CameraX ao analisador
 *  skipped_not_due/s frames descartados antes da conversão (nenhum modelo devido)
 *  ui_frames/s       quadros desenhados pela interface (Choreographer) — fluidez da tela
 *  ui_jank/s         quadros da interface acima de 33 ms
 *  yolo_frames/s     inferências YOLO concluídas
 *  convert_ms        ImageProxy -> Bitmap
 *  yolo_pre_ms       rotação + letterbox + preenchimento do tensor
 *  yolo_infer_ms     Interpreter.run
 *  yolo_post_ms      decodificação + NMS
 *  pipeline_ms       frame inteiro (conversão a callbacks), inclui fluxo óptico e tracking
 *  frame_age_ms      idade do frame ao terminar o processamento
 *  overlay_ui_ms     atualização das boxes na thread principal
 *  ffnet_ms          inferência FFNet (somente na espera por rua)
 */
class PerfStats(
    private val reportEveryMs: Long = 5000,
    private val capacity: Int = 512,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val sink: (String) -> Unit = { android.util.Log.i("SenseWalkPerf", it) }
) {
    private class Ring(capacity: Int) {
        val values = FloatArray(capacity)
        var size = 0
        var next = 0
        fun add(v: Float) { values[next] = v; next = (next + 1) % values.size; if (size < values.size) size++ }
        fun snapshot(): FloatArray = values.copyOf(size).also { it.sort() }
        fun clear() { size = 0; next = 0 }
    }

    private val timings = LinkedHashMap<String, Ring>()
    private val counters = LinkedHashMap<String, Long>()
    private var windowStart = -1L

    @Synchronized fun record(metric: String, valueMs: Float) {
        timings.getOrPut(metric) { Ring(capacity) }.add(valueMs)
    }
    @Synchronized fun count(metric: String, by: Long = 1) {
        counters[metric] = (counters[metric] ?: 0L) + by
    }

    /** Gera o relatório da janela, se vencida; retorna o texto (ou null). Zera a janela. */
    @Synchronized fun maybeReport(): String? {
        val now = clock()
        if (windowStart < 0) { windowStart = now; return null }
        val elapsed = now - windowStart
        if (elapsed < reportEveryMs) return null
        val seconds = elapsed / 1000f
        val parts = mutableListOf<String>()
        for ((name, total) in counters) parts += "$name=${f1(total / seconds)}/s"
        for ((name, ring) in timings) {
            if (ring.size == 0) continue
            val sorted = ring.snapshot()
            parts += "$name(avg=${f1(sorted.average())} p50=${f1(percentile(sorted, .50f))} " +
                "p95=${f1(percentile(sorted, .95f))} n=${sorted.size})"
        }
        counters.clear(); timings.values.forEach { it.clear() }
        windowStart = now
        val text = parts.joinToString(" ")
        if (text.isNotEmpty()) sink(text)
        return text
    }

    private fun f1(v: Number) = String.format(java.util.Locale.ROOT, "%.1f", v.toDouble())

    companion object {
        /** Percentil por posição mais próxima em lista ordenada. */
        fun percentile(sorted: FloatArray, q: Float): Float {
            if (sorted.isEmpty()) return 0f
            val index = (kotlin.math.ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }
}

/** Instância única do processo (analisador, detector e UI registram aqui). */
object Perf {
    val stats = PerfStats()
}
