package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fields with the same fully qualified name are one field (PDF 32000 §12.7.3.1). Some programs
 * write such a name as separate field dictionaries instead of widgets under one parent; filling
 * one must fill the other too, drawn in its own box (decisions §62), or Acrobat shows whichever
 * value it reads first and the typed value seems lost.
 */
@RunWith(AndroidJUnit4::class)
class FormTwinsTest {
    @Before
    fun setUp() = Fixtures.init()

    @Test
    fun fillingOneFieldGivesItsTwinTheSameValue() {
        var doc = Fixtures.open(Fixtures.pdf())
        // Two root fields named "Name", each a field and widget in one dictionary.
        FormFixtures.addFields(
            doc,
            listOf(
                FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f), border = false),
                FormFixtures.Spec("Name", "Tx", Box(40f, 300f, 240f, 324f), border = false),
            ),
        )
        doc = Fixtures.reopen(doc)
        try {
            val both = doc.formFields().filter { it.name == "Name" }
            assertEquals(2, both.size)
            doc.setField(0, both[0].index, text = "Ahmed")
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("Ahmed", "Ahmed"), doc.formFields().filter { it.name == "Name" }.map { it.value })
            val g = Fixtures.render(doc)
            assertTrue("first field empty", g.inkIn(40, 40, 240, 64) > 30)
            assertTrue("twin empty", g.inkIn(40, 300, 240, 324) > 30)
        } finally {
            doc.close()
        }
    }

    @Test
    fun aFieldWithAnotherNameIsLeftAlone() {
        var doc = Fixtures.open(Fixtures.pdf())
        FormFixtures.addFields(
            doc,
            listOf(
                FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f), border = false),
                FormFixtures.Spec("Other", "Tx", Box(40f, 300f, 240f, 324f), border = false),
            ),
        )
        doc = Fixtures.reopen(doc)
        try {
            doc.setField(0, doc.formFields().first { it.name == "Name" }.index, text = "Ahmed")
            doc = Fixtures.reopen(doc)
            assertEquals("", doc.formFields().first { it.name == "Other" }.value)
        } finally {
            doc.close()
        }
    }
}
