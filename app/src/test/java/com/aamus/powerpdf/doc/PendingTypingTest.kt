package com.aamus.powerpdf.doc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisions §52: text still being typed in a form field is committed first. */
class PendingTypingTest {
    @Test
    fun everyPendingCommitRunsOnceBeforeTheDocumentIsRead() = runBlocking {
        val typing = PendingTyping()
        val done = ArrayList<String>()
        typing.set("name") { done.add("name"); true }
        typing.set("city") { done.add("city"); true }
        assertTrue(typing.has())
        assertTrue(typing.commit())
        assertEquals(listOf("name", "city"), done)
        assertFalse(typing.has())
        // Committed once: a second Save does not fill the field again.
        assertTrue(typing.commit())
        assertEquals(listOf("name", "city"), done)
    }

    @Test
    fun aFieldLeftOrAbandonedIsForgotten() = runBlocking {
        val typing = PendingTyping()
        var runs = 0
        typing.set("name") { runs++; true }
        typing.set("name", null)
        assertTrue(typing.commit())
        assertEquals(0, runs)
    }

    @Test
    fun aFailedCommitStillLetsTheOthersRunAndIsReported() = runBlocking {
        val typing = PendingTyping()
        var ran = false
        typing.set("a") { throw IllegalStateException("refused") }
        typing.set("b") { ran = true; true }
        assertFalse(typing.commit())
        assertTrue(ran)
        typing.set("c") { false }
        assertFalse(typing.commit())
    }

    @Test
    fun registeringTheSameFieldAgainReplacesItsCommit() = runBlocking {
        val typing = PendingTyping()
        val done = ArrayList<String>()
        typing.set("name") { done.add("old"); true }
        typing.set("name") { done.add("new"); true }
        typing.commit()
        assertEquals(listOf("new"), done)
    }
}
