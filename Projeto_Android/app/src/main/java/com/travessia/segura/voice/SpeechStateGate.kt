package com.travessia.segura.voice

/** Estados sem dependência de Android. Tempo não rearma um aviso: só uma mudança real. */
class SpeechStateGate {
    private data class Entry(var current: String? = null, var candidate: String? = null,
        var since: Long = 0, var spoken: Boolean = false)
    private val entries = mutableMapOf<String,Entry>()
    fun observe(topic: String, state: String, now: Long, settleMs: Long = 0): Boolean {
        val entry = entries.getOrPut(topic) { Entry() }
        if (entry.candidate != state) { entry.candidate = state; entry.since = now }
        if (now-entry.since < settleMs) return false
        if (entry.current != state) { entry.current = state; entry.spoken = false }
        return true
    }
    fun current(topic: String): String? = entries[topic]?.current
    fun needsSpeech(topic: String, state: String): Boolean = entries[topic]?.let { it.current == state && !it.spoken } ?: false
    fun markSpoken(topic: String, state: String) { entries[topic]?.takeIf { it.current == state }?.spoken = true }
    fun reset(prefix: String) { entries.keys.removeAll { it.startsWith(prefix) } }
}
