package com.aamus.powerpdf.doc

import kotlinx.coroutines.CancellationException

/**
 * Text still being typed in a form field (desktop src/shared/typing.ts, decisions §82
 * there, §52 here). The field's editor keeps what the person types until they leave the field;
 * Save, Save as, Share, the tools that read the document and leaving it commit it first, so what was
 * typed is never left out of what is saved or shared.
 *
 * One per open document ([DocumentSession.typing]). A field registers its commit while it is being
 * typed in, and clears it when the typing is committed by leaving the field (Done, Next, a tap
 * elsewhere) or abandoned (Back › Discard). Main thread.
 */
class PendingTyping {
    private val pending = LinkedHashMap<String, suspend () -> Boolean>()

    /** Registers (or, with null, forgets) the commit of one field. The commit answers false when it failed. */
    fun set(field: String, commit: (suspend () -> Boolean)?) {
        if (commit == null) pending.remove(field) else pending[field] = commit
    }

    fun has(): Boolean = pending.isNotEmpty()

    /**
     * Runs every pending commit, in the order they were registered, each once. False when one of
     * them failed (the field says why; the caller should not go on as if it were saved).
     */
    suspend fun commit(): Boolean {
        if (pending.isEmpty()) return true
        val list = pending.values.toList()
        pending.clear()
        var ok = true
        for (c in list) {
            try {
                if (!c()) ok = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ok = false
            }
        }
        return ok
    }
}
