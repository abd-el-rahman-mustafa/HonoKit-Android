package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {
    private fun words(s: String, page: Int = 0) = s.split(' ').filter { it.isNotEmpty() }.map { DocWord(it, page) }

    @Test
    fun sameTextHasNoChanges() {
        assertEquals(emptyList<Change>(), TextDiff.changes(words("the quick brown fox"), words("the quick brown fox")))
    }

    @Test
    fun aReplacedWordIsOneChangeWithContext() {
        val c = TextDiff.changes(words("the quick brown fox jumps"), words("the quick red fox jumps"))!!
        assertEquals(1, c.size)
        assertEquals("brown", c[0].removed)
        assertEquals("red", c[0].added)
        assertEquals("the quick", c[0].before)
        assertEquals("fox jumps", c[0].after)
    }

    @Test
    fun insertionsAndDeletions() {
        // Words longer than three letters between them keep the two changes apart (decisions §52).
        val c = TextDiff.changes(words("alpha bravo charlie delta echo"), words("alpha charlie delta xray echo"))!!
        assertEquals(listOf("bravo" to "", "" to "xray"), c.map { it.removed to it.added })
    }

    // --- one rewritten sentence is one change (desktop compare.ts joinRewrites; decisions §52) ---

    /** Seen on the owner's phone (2026-10-09): the shared word "in" split this into two changes. */
    @Test
    fun aRewrittenSentenceIsOneChange() {
        val a = words("Page 3, paragraph 5: the same words in both versions.")
        val b = words("This sentence was rewritten in the second version.")
        val c = TextDiff.changes(a, b)!!
        assertEquals(1, c.size)
        assertEquals("Page 3, paragraph 5: the same words in both versions.", c[0].removed)
        assertEquals("This sentence was rewritten in the second version.", c[0].added)
    }

    @Test
    fun aRewrittenSentenceInsideTextIsOneChangeWithItsContext() {
        val a = words("Intro words stay here. Page 3, paragraph 5: the same words in both versions. Closing words stay here.")
        val b = words("Intro words stay here. This sentence was rewritten in the second version. Closing words stay here.")
        val c = TextDiff.changes(a, b)!!
        assertEquals(1, c.size)
        assertEquals("Intro words stay here.", c[0].before)
        assertEquals("Closing words stay here.", c[0].after)
    }

    @Test
    fun anArabicShortWordDoesNotSplitARewrite() {
        val c = TextDiff.changes(words("كتب الطالب الدرس في المكتبة صباحا"), words("قرأ المعلم الكتاب في البيت مساء"))!!
        assertEquals(1, c.size)
    }

    @Test
    fun twoEditsStayApartWhenMoreThanTwoWordsOrALongerWordLieBetween() {
        assertEquals(2, TextDiff.changes(words("The red car and the blue bus went home"), words("The green car and the yellow bus went home"))!!.size)
        assertEquals(2, TextDiff.changes(words("Invoice total 1,000 AED paid yesterday 1,037 AED"), words("Invoice total 1,200 AED paid yesterday 1,040 AED"))!!.size)
        // One word of four letters between.
        assertEquals(2, TextDiff.changes(words("one red bird sang"), words("one blue bird cried"))!!.size)
    }

    @Test
    fun aSingleChangedWordIsStillFound() {
        val c = TextDiff.changes(words("The quick brown fox jumps over the lazy dog"), words("The quick brown fox jumps over the lazy cat"))!!
        assertEquals("dog" to "cat", c.single().removed to c.single().added)
    }

    @Test
    fun changesOnTwoPagesAreNeverJoined() {
        val a = words("alpha bravo of", 0) + words("charlie delta", 1)
        val b = words("alpha xray of", 0) + words("yankee delta", 1)
        val c = TextDiff.changes(a, b)!!
        assertEquals(2, c.size)
        assertEquals(listOf(0, 1), c.map { it.oldPage })
    }

    @Test
    fun arabicFoldingIgnoresLetterFormsAndMarks() {
        // Alef forms and a vowel mark differ; the words are the same.
        assertEquals(emptyList<Change>(), TextDiff.changes(words("إلى المدرسة"), words("الى المَدرسة")))
        val c = TextDiff.changes(words("ذهب أحمد إلى المكتبة"), words("ذهب أحمد إلى البيت"))!!
        assertEquals("المكتبة", c.single().removed)
        assertEquals("البيت", c.single().added)
    }

    @Test
    fun longDocumentWithScatteredEdits() {
        val rnd = java.util.Random(4)
        val a = (0 until 20_000).map { DocWord("w${rnd.nextInt(5000)}", it / 400) }
        val b = a.toMutableList()
        for (i in 0 until 60) b[i * 300 + 7] = DocWord("changed$i", b[i * 300 + 7].page)
        val c = TextDiff.changes(a, b)!!
        assertEquals(60, c.size)
    }

    /** Past MAX_D changes Myers alone gave up; unique words anchor the two sides first (patience). */
    @Test
    fun aLongDocumentWithThousandsOfChangesFinishes() {
        val rnd = java.util.Random(7)
        val a = (0 until 120_000).map { DocWord("w${rnd.nextInt(2_000_000)}", it / 400) }
        val b = a.toMutableList()
        for (i in 0 until 4000) b[i * 30 + 11] = DocWord("changed$i", b[i * 30 + 11].page)
        // A moved word and an inserted paragraph too.
        val more = b.toMutableList().apply { addAll(5000, (0 until 50).map { DocWord("inserted$it", 12) }) }
        val started = System.nanoTime()
        val c = TextDiff.changes(a, more)!!
        assertEquals(4001, c.size)
        assertEquals("changed0", c.first().added)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 20_000_000_000L)
    }

    @Test
    fun unrelatedDocumentsAreTooDifferent() {
        val a = (0 until 8000).map { DocWord("a$it", 0) }
        val b = (0 until 8000).map { DocWord("b$it", 0) }
        assertEquals(null, TextDiff.changes(a, b))
    }

    @Test
    fun pagesAreReported() {
        val a = words("one two", 0) + words("three four", 1)
        val b = words("one two", 0) + words("three five", 1)
        val c = TextDiff.changes(a, b)!!
        assertEquals(1, c.single().oldPage)
        assertEquals(1, c.single().newPage)
    }
}
