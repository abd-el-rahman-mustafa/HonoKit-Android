package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aamus.powerpdf.engine.Fixtures.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Everything the person puts on a page must look the same on a turned page as on a plain one:
 * upright as the page is shown, where it was placed (desktop decisions §78, test/rotated.test.ts).
 * The pages are square, so a page turned by /Rotate shows at the same size as an unturned one and
 * the whole rendering can be compared. Some pages have a MediaBox that does not start at 0,0.
 */
@RunWith(AndroidJUnit4::class)
class RotatedTest {
    @Before
    fun setUp() = Fixtures.init()

    private val red = Rgb(0.75f, 0.22f, 0.17f)
    private val blue = Rgb(0.11f, 0.3f, 0.85f)
    private val green = Rgb(0.09f, 0.64f, 0.29f)
    private val black = Rgb(0f, 0f, 0f)

    private fun PdfDoc.first(): MarkInfo = marks(0).first()

    private val cases: Map<String, (PdfDoc) -> Unit> = linkedMapOf(
        "stamp" to { d -> d.addStamp(0, 180f, 140f, "APPROVED", "by Test, 2026-10-08", red, "Test") },
        "stamp (Arabic)" to { d -> d.addStamp(0, 250f, 300f, "معتمد", null, blue, "Test") },
        "text box" to { d -> d.addTextBox(0, 60f, 300f, "A text box that wraps onto a second line", green, 12f, 200f, "Test") },
        "text box (Arabic)" to { d -> d.addTextBox(0, 440f, 200f, "نص عربي في مربع نص", blue, 14f, 200f, "Test") },
        "rectangle" to { d -> d.addShape(0, MarkKind.Rect, 50f, 80f, 220f, 130f, red, 4f, "Test") },
        "ellipse" to { d -> d.addShape(0, MarkKind.Ellipse, 260f, 300f, 460f, 360f, blue, 3f, "Test") },
        "arrow" to { d -> d.addShape(0, MarkKind.Arrow, 60f, 420f, 300f, 380f, black, 3f, "Test") },
        "pen" to { d -> d.addInk(0, listOf(listOf(50f to 50f, 120f to 90f, 200f to 60f, 260f to 140f)), black, 3f, 1f, "Test") },
        "note" to { d -> d.addNote(0, 300f, 120f, "A note", Rgb(0.98f, 0.8f, 0.08f), "Test") },
        "note moved and revised" to { d ->
            d.addNote(0, 300f, 120f, "A note", Rgb(0.98f, 0.8f, 0.08f), "Test")
            d.first().let { m -> d.moveMark(0, m.index, m.box, -150f, 200f) }
            d.first().let { m -> d.setMarkText(0, m.index, m.box, "Changed") }
            d.first().let { m -> d.recolourMark(0, m.index, m.box, blue) }
        },
        "stamp moved" to { d ->
            d.addStamp(0, 180f, 140f, "DRAFT", null, red, "Test")
            d.first().let { m -> d.moveMark(0, m.index, m.box, 90f, 160f) }
        },
        "stamp revised" to { d ->
            d.addStamp(0, 180f, 140f, "DRAFT", null, red, "Test")
            d.first().let { m -> d.setMarkText(0, m.index, m.box, "FINAL VERSION") }
            d.first().let { m -> d.recolourMark(0, m.index, m.box, green) }
        },
        "text box moved" to { d ->
            d.addTextBox(0, 60f, 300f, "Moved box", green, 14f, 200f, "Test")
            d.first().let { m -> d.moveMark(0, m.index, m.box, 120f, -150f) }
        },
        "signature (picture)" to { d -> d.addPicture(0, Fixtures.arrowPng(), 100f, 150f, 260f, 250f) },
        "added text" to { d -> d.addText(0, 60f, 120f, 400f, "Added words Hello", TextStyle(22f, black)) },
        "added text (Arabic)" to { d -> d.addText(0, 440f, 200f, 380f, "نص مضاف هنا", TextStyle(22f, blue)) },
        "watermark" to { d -> d.addWatermark(listOf(0), "CONFIDENTIAL", TextStyle(40f, red, opacity = 0.6f), 30f, VPos.Middle, HAlign.Center) },
        "watermark (top left)" to { d -> d.addWatermark(listOf(0), "سري", TextStyle(30f, red), 0f, VPos.Top, HAlign.Left) },
        "picture watermark" to { d -> d.addPictureWatermark(listOf(0), Fixtures.arrowPng(), 0.4f, 0.7f, 20f) },
        "page numbers" to { d -> d.addPageNumbers(listOf(0), "Page {n} of {N}", 1, TextStyle(14f, black), VPos.Bottom, HAlign.Right, 28f, false) },
        "page numbers (top, Arabic digits)" to { d -> d.addPageNumbers(listOf(0), "صفحة {n} من {N}", 7, TextStyle(14f, black), VPos.Top, HAlign.Left, 28f, true) },
        "picture moved" to { d ->
            d.addPicture(0, Fixtures.arrowPng(), 100f, 150f, 260f, 250f)
            d.movePicture(0, d.pictures(0).single().box, Box(220f, 300f, 380f, 400f))
        },
        "redaction marked" to { d -> d.markForRedaction(listOf(0 to Box(55f, 95f, 200f, 130f)), "Test") },
        "redaction applied" to { d ->
            d.addText(0, 60f, 100f, 400f, "Secret words here", TextStyle(22f, black))
            d.markForRedaction(listOf(0 to Box(55f, 95f, 200f, 130f)), "Test")
            d.applyRedactions(black, "X", sanitize = false)
        },
    )

    private fun run(shape: Shape, steps: (PdfDoc) -> Unit): Fixtures.Grey {
        var doc = Fixtures.open(Fixtures.pdf(shape))
        try {
            steps(doc)
            doc = Fixtures.reopen(doc)
            return Fixtures.render(doc)
        } finally {
            doc.close()
        }
    }

    @Test
    fun everyToolLooksTheSameOnATurnedPage() {
        val bad = ArrayList<String>()
        for ((name, steps) in cases) {
            val plain = run(Shape(0), steps)
            assertTrue("$name drew nothing on a plain page", plain.inked > if (name == "redaction marked") 50 else 100)
            for (shape in Fixtures.SHAPES) {
                val d = Fixtures.difference(plain, run(shape, steps))
                if (d > 0.03f) bad.add("$name on $shape: ${(d * 100).toInt()}% differs")
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    /** Fields as Acrobat makes them on a turned page: /MK /R is the page's turn. */
    private fun withFields(shape: Shape, border: Boolean = true, appearance: Boolean = true): PdfDoc {
        val doc = Fixtures.open(Fixtures.pdf(shape))
        FormFixtures.addFields(
            doc,
            listOf(
                FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f), border = border, appearance = appearance),
                FormFixtures.Spec("Agree", "Btn", Box(260f, 40f, 280f, 60f), border = border, appearance = appearance),
                FormFixtures.Spec("City", "Ch", Box(40f, 90f, 200f, 114f), options = listOf("Dubai", "Sharjah"), border = border, appearance = appearance),
                FormFixtures.Spec("Notes", "Tx", Box(40f, 140f, 300f, 200f), multiline = true, border = border, appearance = appearance),
            ),
            turn = shape.rotate,
        )
        return Fixtures.reopen(doc)
    }

    private fun fields(shape: Shape, fill: Boolean, border: Boolean = true): Fixtures.Grey {
        var doc = withFields(shape, border, appearance = fill)
        try {
            if (fill) {
                for (f in doc.formFields(0)) when (f.name) {
                    "Name" -> doc.setField(0, f.index, text = "Ahmed أحمد")
                    "Agree" -> doc.setField(0, f.index, checked = true)
                    "City" -> doc.setField(0, f.index, text = "Sharjah")
                    "Notes" -> doc.setField(0, f.index, text = "ملاحظات تلتف على سطرين داخل الحقل الطويل")
                }
                doc = Fixtures.reopen(doc)
            }
            return Fixtures.render(doc)
        } finally {
            doc.close()
        }
    }

    @Test
    fun formFieldsLookTheSameOnATurnedPage() {
        val bad = ArrayList<String>()
        // Empty with frames; filled with frames; filled without frames (only the values drawn).
        for ((fill, border) in listOf(false to true, true to true, true to false)) {
            val plain = fields(Shape(0), fill, border)
            if (!border) Fixtures.dump(plain, "fields-plain")
            assertTrue("fields drew nothing (fill=$fill)", plain.inked > 100)
            for (shape in Fixtures.SHAPES) {
                val got = fields(shape, fill, border)
                if (!border) Fixtures.dump(got, "fields-${shape.rotate}-${shape.ox.toInt()}")
                val d = Fixtures.difference(plain, got)
                if (d > 0.03f) bad.add("${if (fill) "filled" else "empty"}${if (border) "" else " frameless"} fields on $shape: ${(d * 100).toInt()}% differs")
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    /** A2: a stamp changed after its page was turned is drawn again upright, where it shows. */
    @Test
    fun aStampRevisedAfterThePageTurnedStaysWhereItShows() {
        for (turn in listOf(90, 180, 270)) {
            var doc = Fixtures.open(Fixtures.pdf())
            try {
                doc.addStamp(0, 150f, 120f, "DRAFT", "today", red, "Test")
                doc.rotatePages(listOf(0), turn)
                doc = Fixtures.reopen(doc)
                val before = Fixtures.render(doc).inkBox()!!
                val m = doc.first()
                doc.recolourMark(0, m.index, m.box, green)
                doc = Fixtures.reopen(doc)
                val after = Fixtures.render(doc)
                val box = after.inkBox()!!
                val cx0 = (before[0] + before[2]) / 2f
                val cy0 = (before[1] + before[3]) / 2f
                val cx1 = (box[0] + box[2]) / 2f
                val cy1 = (box[1] + box[3]) / 2f
                assertTrue("turn $turn: moved from $cx0,$cy0 to $cx1,$cy1", kotlin.math.abs(cx0 - cx1) < 4 && kotlin.math.abs(cy0 - cy1) < 4)
                // Upright now: the same as a stamp made on the turned page at that place.
                var fresh = Fixtures.open(Fixtures.pdf(Shape(turn)))
                try {
                    fresh.addStamp(0, cx1, cy1, "DRAFT", "today", green, "Test")
                    fresh = Fixtures.reopen(fresh)
                    val d = Fixtures.difference(Fixtures.render(fresh), after)
                    assertTrue("turn $turn: ${(d * 100).toInt()}% differs from an upright stamp", d < 0.05f)
                } finally {
                    fresh.close()
                }
            } finally {
                doc.close()
            }
        }
    }

    /** A2: a stamp changed after the page was cropped stays where it shows. */
    @Test
    fun aStampRevisedAfterACropStaysWhereItShows() {
        var doc = Fixtures.open(Fixtures.pdf())
        try {
            doc.addStamp(0, 250f, 250f, "DRAFT", null, red, "Test")
            doc.cropPages(listOf(0), Margins(60f, 40f, 10f, 20f))
            doc = Fixtures.reopen(doc)
            val before = Fixtures.render(doc).inkBox()!!
            val m = doc.first()
            doc.setMarkText(0, m.index, m.box, "DRAFT")
            doc = Fixtures.reopen(doc)
            val after = Fixtures.render(doc).inkBox()!!
            for (i in 0 until 4) assertTrue("edge $i moved: ${before.toList()} → ${after.toList()}", kotlin.math.abs(before[i] - after[i]) <= 3)
        } finally {
            doc.close()
        }
    }

    /** The note's icon is drawn from the PDF-space top-left of /Rect (NoRotate): that corner is where it was put. */
    @Test
    fun aNoteSitsWhereItWasPut() {
        for (shape in listOf(Shape(0)) + Fixtures.SHAPES) {
            var doc = Fixtures.open(Fixtures.pdf(shape))
            try {
                doc.addNote(0, 300f, 120f, "A note", Rgb(0.98f, 0.8f, 0.08f), "Test")
                doc = Fixtures.reopen(doc)
                val box = Fixtures.render(doc).inkBox()!!
                assertTrue("$shape: icon at ${box.toList()}", kotlin.math.abs(box[0] - 300) <= 3 && kotlin.math.abs(box[1] - 120) <= 3)
            } finally {
                doc.close()
            }
        }
    }

}
