package com.choplab.sampler.audio

/**
 * Serializes runtime command admission with engine start/shutdown.
 *
 * The caller must use the same [lifecycleLock] when changing the running state and clearing the
 * command mailbox. That makes shutdown's "stop, then clear" boundary atomic with producers: a
 * command is either admitted before the clear or rejected after the engine has stopped.
 */
internal class RuntimeCommandAdmission(
    private val lifecycleLock: Any,
    private val isRunning: () -> Boolean,
) {
    fun offer(admit: () -> Boolean): Boolean = synchronized(lifecycleLock) {
        isRunning() && admit()
    }
}
