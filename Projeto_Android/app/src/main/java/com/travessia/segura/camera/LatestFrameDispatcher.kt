package com.travessia.segura.camera

/** Uma entrega pendente na UI. Frames substituídos são liberados mesmo com a UI bloqueada. */
class LatestFrameDispatcher<T : Any>(
    private val post: (() -> Unit) -> Unit,
    private val discard: (T) -> Unit,
    private val consume: (T) -> Unit
) {
    private var pending: T? = null
    private var scheduled = false

    fun offer(value: T) {
        val schedule: Boolean
        val previous: T?
        synchronized(this) {
            previous = pending
            pending = value
            schedule = !scheduled
            scheduled = true
        }
        previous?.let(discard)
        if (schedule) post {
            val latest = synchronized(this) {
                val result = pending
                pending = null
                scheduled = false
                result
            }
            latest?.let(consume)
        }
    }

    fun clear() {
        val previous = synchronized(this) { pending.also { pending = null } }
        previous?.let(discard)
    }
}
