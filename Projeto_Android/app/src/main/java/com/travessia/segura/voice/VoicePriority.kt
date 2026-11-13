package com.travessia.segura.voice

enum class VoicePriority(val weight: Int) {
    INFO(10),
    CROSSING(60),
    /** Parar/Cancelar: acima das instruções, abaixo dos alertas de perigo. */
    CONTROL(80),
    SAFETY(100)
}

data class ScheduledVoiceMessage(
    val key: String,
    val text: String,
    val priority: VoicePriority,
    val timestampMs: Long,
    val stateTopic: String? = null,
    val stateValue: String? = null,
    val decisionElapsedMs: Long = 0L
)

/** Pequena fila determinística, separada do Android TTS para testes locais. */
class VoiceMessageScheduler(private val maxSize: Int = 3) {
    private val queue = mutableListOf<ScheduledVoiceMessage>()

    @Synchronized
    fun offer(message: ScheduledVoiceMessage): Boolean {
        if (queue.size >= maxSize) {
            val lowest = queue.minByOrNull { it.priority.weight } ?: return false
            if (lowest.priority.weight >= message.priority.weight) return false
            queue.remove(lowest)
        }
        queue += message
        queue.sortWith(compareByDescending<ScheduledVoiceMessage> { it.priority.weight }.thenBy { it.timestampMs })
        return true
    }

    @Synchronized
    fun poll(): ScheduledVoiceMessage? = if (queue.isEmpty()) null else queue.removeAt(0)

    @Synchronized
    fun clear() = queue.clear()

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized fun contains(key: String): Boolean = queue.any { it.key == key }
    @Synchronized fun remove(key: String) { queue.removeAll { it.key == key } }
    @Synchronized fun removePrefix(prefix: String) { queue.removeAll { it.key.startsWith(prefix) } }
    @Synchronized fun dropBelow(priority: VoicePriority) { queue.removeAll { it.priority.weight < priority.weight } }
    @Synchronized fun hasHigherPriorityThan(priority: VoicePriority): Boolean =
        queue.any { it.priority.weight > priority.weight }
}
