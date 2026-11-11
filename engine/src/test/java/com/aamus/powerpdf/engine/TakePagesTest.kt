package com.aamus.powerpdf.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages leaving a protected file for an unprotected one need copying and assembling (desktop §116). */
class TakePagesTest {
    private fun rights(edit: Boolean, assemble: Boolean, copy: Boolean, full: Boolean = false) =
        Rights(encrypted = true, full = full, edit = edit, assemble = assemble, annotate = false, forms = false, copy = copy, print = true)

    @Test
    fun copyingAndAssemblingAreBothNeeded() {
        assertFalse(rights(edit = false, assemble = false, copy = false).takesPages)
        // "Any change except extracting pages": assembling allowed, copying withheld.
        assertFalse(rights(edit = true, assemble = true, copy = false).takesPages)
        assertFalse(rights(edit = false, assemble = false, copy = true).takesPages)
        assertTrue(rights(edit = false, assemble = true, copy = true).takesPages)
        assertTrue(rights(edit = true, assemble = false, copy = true).takesPages)
    }

    @Test
    fun thePermissionsPasswordLiftsIt() {
        assertTrue(rights(edit = false, assemble = false, copy = false, full = true).takesPages)
        assertTrue(Rights(false, true, true, true, true, true, true, true).takesPages)
    }
}
