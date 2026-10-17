package com.echo.app.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.echo.app.meeting.CloudRec
import com.echo.app.meeting.Frame
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 3 · V6/V7 — the PDF Law sends on WhatsApp/email: a site walk REPORT (overview, progress, problems each with a
 * photo and the moment it was seen, counts, safety, next steps, questions) or, for any other video, its NOTES with the
 * key-moment photos. Photos come from the ORIGINAL video on the phone (full quality) when it's there, else from the
 * key-moment photos in the cloud ([remotePhoto]). Plain A4, black on white, readable when printed.
 */
object ReportPdf {
    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 40f // margin

    private val WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

    /** Builds the PDF into [out]. [remotePhoto] loads a key-moment photo from the cloud (path → bytes) when the original isn't on the phone. */
    suspend fun build(rec: CloudRec, original: File?, out: File, remotePhoto: suspend (String) -> ByteArray?): File {
        val s = rec.summary ?: JSONObject()
        val report = s.optJSONObject("report")
        val walk = rec.kind == "walk" || report != null
        val photos = PhotoSource(original, rec.frames, remotePhoto)
        val w = Writer()
        try {
            w.title(rec.title.ifBlank { if (walk) "Site walk report" else "Video notes" })
            val meta = listOfNotNull(
                report?.optString("place")?.takeIf { it.isNotBlank() },
                if (rec.startedAt > 0) WHEN.format(Instant.ofEpochMilli(rec.startedAt)) else null,
                if (rec.durationS > 0) (if (rec.durationS < 60) "${rec.durationS} s" else "${rec.durationS / 60} min") + if (walk) " walk" else " video" else null,
            ).joinToString(" · ")
            if (meta.isNotBlank()) w.small(meta)
            w.gap(6f)
            w.heading(if (walk) "Overview" else "In short")
            w.para(report?.optString("overview")?.takeIf { it.isNotBlank() } ?: s.optString("in_short"))

            if (report != null) {
                w.bullets("Progress — done or under way", report.optJSONArray("progress").strings())
                val problems = report.optJSONArray("problems").objects()
                if (problems.isNotEmpty()) {
                    w.heading("Problems (${problems.size})")
                    for ((i, p) in problems.withIndex()) {
                        val t = p.optDouble("t", -1.0)
                        val sev = p.optString("severity").uppercase()
                        val where = p.optString("where")
                        w.problem(
                            "${i + 1}. ${p.optString("what")}",
                            listOfNotNull(sev.ifBlank { null }, where.ifBlank { null }, if (t >= 0) "seen at ${clock(t)}" else null).joinToString(" · "),
                            if (t >= 0) photos.at(t) else null,
                            sevColor(sev),
                        )
                    }
                }
                val counts = report.optJSONArray("counts").objects()
                if (counts.isNotEmpty()) {
                    w.heading("Counts")
                    counts.forEach { c -> w.row(c.optString("item"), c.optString("number") + "  (" + c.optString("sure") + ")") }
                    w.small("Counts from the video are estimates unless someone said the number.")
                }
                w.bullets("Safety", report.optJSONArray("safety").strings())
                w.bullets("Next steps", report.optJSONArray("next_steps").strings())
                w.bullets("Questions", report.optJSONArray("questions").strings())
            } else {
                // A video: key moments with their photos, then the meeting-style lists.
                if (rec.frames.isNotEmpty()) {
                    w.heading("Key moments")
                    rec.frames.forEach { f -> w.problem(f.why, "at ${clock(f.t)}", photos.at(f.t), Color.DKGRAY) }
                }
                w.bullets("Action items", s.optJSONArray("action_items").objects().map { a ->
                    a.optString("task") + listOf(a.optString("owner"), a.optString("due")).filter { it.isNotBlank() }.joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }
                })
                val numbers = s.optJSONArray("numbers").objects()
                if (numbers.isNotEmpty()) { w.heading("Numbers"); numbers.forEach { n -> w.row(n.optString("label"), n.optString("value")) } }
                w.bullets("Decisions", s.optJSONArray("decisions").strings())
                w.bullets("Follow-ups", s.optJSONArray("follow_ups").strings())
            }
            w.gap(10f)
            w.small("Made by JARVIS from the video and what was said. Check anything important before acting on it.")
            out.parentFile?.mkdirs()
            w.finish()
            out.outputStream().use { w.doc.writeTo(it) }
        } finally {
            w.close(); photos.close()
        }
        return out
    }

    private fun sevColor(sev: String) = when (sev) {
        "HIGH" -> Color.rgb(0xC6, 0x28, 0x28)
        "MEDIUM" -> Color.rgb(0xE0, 0x8A, 0x00)
        else -> Color.rgb(0x55, 0x65, 0x70)
    }

    fun clock(t: Double): String { val s = t.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

    private fun org.json.JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    private fun org.json.JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** A photo for time t: from the original on the phone, else the nearest key-moment photo in the cloud (within 8 s). */
    private class PhotoSource(original: File?, private val frames: List<Frame>, private val remote: suspend (String) -> ByteArray?) {
        private val r: MediaMetadataRetriever? = original?.takeIf { it.exists() }?.let { f -> runCatching { MediaMetadataRetriever().apply { setDataSource(f.absolutePath) } }.getOrNull() }
        suspend fun at(t: Double): Bitmap? {
            r?.let { m -> runCatching { m.getScaledFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, 640, 640) }.getOrNull()?.let { return it } }
            val f = frames.minByOrNull { kotlin.math.abs(it.t - t) }?.takeIf { kotlin.math.abs(it.t - t) <= 8 } ?: return null
            return remote(f.path)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        fun close() { runCatching { r?.release() } }
    }

    /** Lays text and photos down the page, starting a new page when one is full. */
    private class Writer {
        val doc = PdfDocument()
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private lateinit var c: Canvas
        private var y = 0f
        private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(0x1A, 0x1A, 0x1A) }
        private val bold = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        private val small = TextPaint(body).apply { textSize = 9f; color = Color.rgb(0x60, 0x60, 0x60) }
        private val head = TextPaint(bold).apply { textSize = 13.5f; color = Color.rgb(0x0B, 0x4F, 0x6C) }
        private val big = TextPaint(bold).apply { textSize = 20f }
        private val width get() = (W - 2 * M).toInt()

        init { newPage() }

        private fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page!!.canvas
            y = M
            c.drawText("JARVIS", W - M - 40f, H - 20f, small)
            c.drawText("Page $pageNo", M, H - 20f, small)
        }
        private fun need(h: Float) { if (y + h > H - M) newPage() }
        private fun layout(text: String, p: TextPaint, w: Int = width) = StaticLayout.Builder.obtain(text, 0, text.length, p, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
        private fun draw(text: String, p: TextPaint, x: Float = M, w: Int = width) {
            val l = layout(text, p, w)
            need(l.height.toFloat())
            c.save(); c.translate(x, y); l.draw(c); c.restore()
            y += l.height
        }
        fun title(t: String) { draw(t, big); y += 4f }
        fun small(t: String) { draw(t, small); y += 2f }
        fun gap(h: Float) { y += h }
        fun heading(t: String) { y += 12f; need(40f); draw(t, head); y += 4f }
        fun para(t: String) { if (t.isNotBlank()) { draw(t, body); y += 4f } }
        fun bullets(title: String, items: List<String>) {
            if (items.isEmpty()) return
            heading(title)
            items.forEach { draw("•  $it", body, M + 4f, width - 4); y += 3f }
        }
        fun row(label: String, value: String) {
            val l = layout(label, body, width / 2 - 8); val v = layout(value, bold, width / 2)
            val h = maxOf(l.height, v.height).toFloat()
            need(h + 4f)
            c.save(); c.translate(M, y); l.draw(c); c.restore()
            c.save(); c.translate(M + width / 2f, y); v.draw(c); c.restore()
            y += h + 4f
        }
        /** A titled item with a coloured bar, a line underneath and (if any) a photo beside it. */
        fun problem(what: String, sub: String, photo: Bitmap?, color: Int) {
            val photoW = if (photo != null) 200f else 0f
            val textW = (width - photoW - (if (photo != null) 12f else 0f) - 10f).toInt()
            val l = layout(what, bold, textW); val s = layout(sub, small, textW)
            val photoH = photo?.let { photoW * it.height / it.width } ?: 0f
            val h = maxOf(l.height + s.height + 6f, photoH)
            need(h + 10f)
            c.drawRect(RectF(M, y, M + 4f, y + h), Paint().apply { this.color = color })
            c.save(); c.translate(M + 10f, y); l.draw(c); c.translate(0f, l.height + 4f); s.draw(c); c.restore()
            if (photo != null) c.drawBitmap(photo, null, RectF(W - M - photoW, y, W - M, y + photoH), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 10f
        }
        fun finish() { page?.let { doc.finishPage(it) }; page = null }
        fun close() { page?.let { runCatching { doc.finishPage(it) } }; page = null; doc.close() }
    }
}
