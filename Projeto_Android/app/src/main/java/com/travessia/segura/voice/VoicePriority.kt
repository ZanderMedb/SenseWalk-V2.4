package com.travessia.segura.voice

enum class VoicePriority(val weight: Int) {
    INFO(10),
    NAVIGATION(30),
    CROSSING(60),
    SAFETY(100)
}

data class ScheduledVoiceMessage(
    val key: String,
    val text: String,
    val priority: VoicePriority,
    val timestampMs: Long
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
}
