package com.aamus.powerpdf.engine.img

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An area to hide, as fractions of the upright picture; [on] false keeps it as it is (tapped off). */
/** An area to hide: a face or plate the detectors found, or one [drawn] by hand. */
data class HideArea(val x: Float, val y: Float, val w: Float, val h: Float, val face: Boolean, val on: Boolean = true, val drawn: Boolean = false)

enum class HideStyle { Blur, Pixels, Box }

object ImageExtras {
    /**
     * A text mark drawn large (so it stays sharp on a big photo) into a transparent bitmap.
     * StaticLayout shapes Arabic and orders mixed Arabic and Latin text.
     */
    fun textStamp(text: String, colour: Int, outline: Boolean, shadow: Boolean, bold: Boolean = true, typeface: Typeface? = null): Bitmap {
        val size = 160f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = colour
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (shadow) setShadowLayer(size * 0.08f, size * 0.03f, size * 0.04f, 0x80000000.toInt())
        }
        val lines = text.ifBlank { " " }
        val width = ceil(lines.split('\n').maxOf { Layout.getDesiredWidth(it, paint) }).toInt() + 1
        val layout = StaticLayout.Builder.obtain(lines, 0, lines.length, paint, max(1, width)).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
        val pad = (size * if (outline) 0.18f else 0.12f).roundToInt()
        val out = Bitmap.createBitmap(width + pad * 2, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(pad.toFloat(), pad.toFloat())
        if (outline) {
            val strokePaint = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.12f
                strokeJoin = Paint.Join.ROUND
                color = if (Color.luminance(colour) > 0.5f) Color.BLACK else Color.WHITE
                clearShadowLayer()
            }
            StaticLayout.Builder.obtain(lines, 0, lines.length, strokePaint, max(1, width)).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build().draw(c)
        }
        layout.draw(c)
        return out
    }

    /**
     * Hides each area: blurred or pixelated past recognition, or a black box. Faces are grown a
     * little and get an oval (a forehead left sharp still identifies a person), as on the desktop.
     */
    fun hide(b: Bitmap, areas: List<HideArea>, style: HideStyle): Bitmap {
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        for (a in areas) {
            if (!a.on) continue
            val grow = if (a.face) 0.2f else 0.06f
            val x0 = max(0f, (a.x - a.w * grow) * b.width)
            val y0 = max(0f, (a.y - a.h * grow * (if (a.face) 1.3f else 1f)) * b.height)
            val x1 = min(b.width.toFloat(), (a.x + a.w * (1 + grow)) * b.width)
            val y1 = min(b.height.toFloat(), (a.y + a.h * (1 + grow)) * b.height)
            val w = (x1 - x0).roundToInt()
            val h = (y1 - y0).roundToInt()
            if (w < 2 || h < 2) continue
            val rect = RectF(x0, y0, x0 + w, y0 + h)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (style == HideStyle.Box) {
                paint.color = Color.BLACK
                c.drawRect(rect, paint)
                continue
            }
            val region = Bitmap.createBitmap(b, x0.roundToInt(), y0.roundToInt(), min(w, b.width - x0.roundToInt()), min(h, b.height - y0.roundToInt()))
            // Down to a few blocks, then up: smooth (blur) or square (pixels).
            val blocks = if (style == HideStyle.Pixels) 10 else 6
            val sw = max(1, (blocks * min(1f, region.width.toFloat() / region.height)).roundToInt())
            val sh = max(1, (blocks * min(1f, region.height.toFloat() / region.width)).roundToInt())
            var hidden = Bitmap.createScaledBitmap(region, sw, sh, true)
            hidden = if (style == HideStyle.Pixels) Bitmap.createScaledBitmap(hidden, region.width, region.height, false)
            else Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(hidden, sw * 3, sh * 3, true), region.width / 4 + 1, region.height / 4 + 1, true), region.width, region.height, true)
            paint.shader = BitmapShader(hidden, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setTranslate(x0.roundToInt().toFloat(), y0.roundToInt().toFloat()) })
            }
            if (a.face) c.drawOval(rect, paint) else c.drawRect(rect, paint)
        }
        return out
    }

    /** Camera details, dates and location, as label → value (empty when the picture has none). */
    fun metadata(file: File): List<Pair<String, String>> {
        val exif = runCatching { ExifInterface(file) }.getOrNull() ?: return emptyList()
        val tags = listOf(
            "Make" to ExifInterface.TAG_MAKE, "Model" to ExifInterface.TAG_MODEL, "Lens" to ExifInterface.TAG_LENS_MODEL,
            "Taken" to ExifInterface.TAG_DATETIME_ORIGINAL, "Changed" to ExifInterface.TAG_DATETIME, "Exposure" to ExifInterface.TAG_EXPOSURE_TIME,
            "Aperture" to ExifInterface.TAG_F_NUMBER, "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "Focal length" to ExifInterface.TAG_FOCAL_LENGTH,
            "Software" to ExifInterface.TAG_SOFTWARE, "Artist" to ExifInterface.TAG_ARTIST, "Copyright" to ExifInterface.TAG_COPYRIGHT,
            "Description" to ExifInterface.TAG_IMAGE_DESCRIPTION, "Owner" to ExifInterface.TAG_CAMERA_OWNER_NAME,
        )
        val out = tags.mapNotNull { (label, tag) -> exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { label to it } }.toMutableList()
        exif.latLong?.let { (lat, lon) -> out.add("Location" to "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)) }
        return out
    }

    fun hasLocation(file: File): Boolean = runCatching { ExifInterface(file).latLong != null }.getOrDefault(false)

    /**
     * Removes camera details and location from [file] in place, without re-encoding the picture
     * (JPEG, PNG, WebP): the pixels stay exactly as they were.
     */
    fun stripMetadata(file: File) {
        val exif = ExifInterface(file)
        // An explicit list, not reflection over ExifInterface's TAG_ fields: R8 inlines and removes
        // those constants in release builds, and the strip would then silently remove nothing.
        for (tag in PRIVATE_TAGS) if (exif.getAttribute(tag) != null) exif.setAttribute(tag, null)
        exif.saveAttributes()
    }

    /** Everything that says who, where, when or with what: location, camera, owner, dates, notes. */
    val PRIVATE_TAGS: List<String> = listOf(
        ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_XMP,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION, ExifInterface.TAG_SUBJECT_DISTANCE,
    )
}
