package de.schliweb.makeacopy.utils.jpeg

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class JpegExporterInstrumentedTest {

    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() {
            // Versuche OpenCV zu laden; je nach Packaging heißt die lib "opencv_java4" o.ä.
            try {
                System.loadLibrary("opencv_java4")
            } catch (e: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("opencv_java3")
                } catch (_: UnsatisfiedLinkError) {
                    // Falls deine App OpenCV selbst initialisiert, kann das auch dort passieren.
                    Log.w("JpegExporterTest", "OpenCV native lib not preloaded; relying on app init.")
                }
            }
        }
    }

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun export_comparison_auto_bwtext_grayscale() {
        val bitmap = makeSyntheticDocument(width = 2480, height = 3508, dpi = 300) // A4-ähnlich

        // Ausgabepfade
        val outAuto = createTempContentUri("auto.jpg")
        val outBw   = createTempContentUri("bw.jpg")
        val outGray = createTempContentUri("gray.jpg")

        // Optionen
        val baseQ = 85
        val optsAuto = JpegExportOptions(baseQ, 0, JpegExportOptions.Mode.AUTO).apply {
            // Demo: ohne Zwang zu Graustufen
            forceGrayscaleJpeg = false
            maxLongEdgeGuardPx = 4096
            roundResizeToMultipleOf8 = true
        }

        val optsBw = JpegExportOptions(baseQ, 0, JpegExportOptions.Mode.BW_TEXT).apply {
            // BW_TEXT konvertiert ohnehin zu Graustufen-JPEG im Exporter
            forceGrayscaleJpeg = false
            maxLongEdgeGuardPx = 4096
            roundResizeToMultipleOf8 = true
        }

        val optsAutoForceGray = JpegExportOptions(baseQ, 0, JpegExportOptions.Mode.AUTO).apply {
            forceGrayscaleJpeg = true
            maxLongEdgeGuardPx = 4096
            roundResizeToMultipleOf8 = true
        }

        // Exporte
        Assert.assertNotNull(JpegExporter.export(ctx, bitmap, optsAuto, outAuto))
        Assert.assertNotNull(JpegExporter.export(ctx, bitmap, optsBw,   outBw))
        Assert.assertNotNull(JpegExporter.export(ctx, bitmap, optsAutoForceGray, outGray))

        // Größen messen
        val sizeAuto = sizeOf(outAuto)
        val sizeBw   = sizeOf(outBw)
        val sizeGray = sizeOf(outGray)

        Log.i("JpegExporterTest", "Sizes (bytes)  AUTO=$sizeAuto  BW_TEXT=$sizeBw  AUTO+Gray=$sizeGray")

        // Erwartung 1: BW_TEXT sollte kleiner sein als AUTO (textlastiges Dokument)
        Assert.assertTrue(
            "BW_TEXT should be smaller than AUTO",
            sizeBw < sizeAuto
        )

        // Erwartung 2: AUTO+forceGrayscaleJpeg <= AUTO
        Assert.assertTrue(
            "AUTO (forced grayscale) should be smaller or equal to AUTO color",
            sizeGray <= sizeAuto
        )

        // Optional: Mindest-Reduktion (10%) für BW_TEXT vs AUTO
        val reduction = (1.0 - (sizeBw.toDouble() / sizeAuto.toDouble()))
        Log.i("JpegExporterTest", "BW_TEXT reduction vs AUTO: ${(reduction * 100).roundToInt()}%")
        Assert.assertTrue(
            "BW_TEXT should be at least ~10% smaller (was ${(reduction * 100).roundToInt()}%)",
            reduction > 0.10
        )
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun createTempContentUri(name: String): Uri {
        val f = File(ctx.cacheDir, "jpeg_exporter_test_$name").apply {
            if (exists()) delete()
            createNewFile()
        }
        return FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.test.fileprovider",
            f
        )
    }

    private fun sizeOf(uri: Uri): Long {
        // Da wir FileProvider nutzen, können wir die zugrundeliegende File-Größe lesen:
        val path = uri.path
        return if (path != null && path.contains("/cache/")) {
            // Heuristik: letzte Pfadkomponente ist die Cache-Datei
            val fileName = path.substringAfterLast("/")
            val f = File(ctx.cacheDir, fileName)
            f.length()
        } else {
            // Fallback über ContentResolver (langsamer)
            ctx.contentResolver.openAssetFileDescriptor(uri, "r").use { afd ->
                afd?.length ?: -1L
            }
        }
    }

    /**
     * Erzeugt eine synthetische, textlastige Dokumentseite mit Kopfzeile, Tabellenlinien
     * und mehreren Textblöcken. Optional ein bisschen Rauschen für realistischere Kompression.
     */
    private fun makeSyntheticDocument(width: Int, height: Int, dpi: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Hintergrund
        canvas.drawColor(Color.WHITE)

        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 3f
        }

        // Kopfzeile
        paintText.textSize = spToPx(20f, dpi)
        canvas.drawText("ACME GmbH – Rechnung 2025-09-01 – #INV-123456", 80f, 140f, paintText)

        // Adresse
        paintText.textSize = spToPx(14f, dpi)
        val startY = 220f
        val lineH = 48f
        val lines = listOf(
            "Max Mustermann",
            "Musterstraße 12",
            "12345 Musterstadt",
            "Deutschland"
        )
        for ((i, s) in lines.withIndex()) {
            canvas.drawText(s, 80f, startY + i * lineH, paintText)
        }

        // Tabelle Kopf
        val tableTop = 520f
        val left = 80f
        val right = width - 80f
        canvas.drawLine(left, tableTop, right, tableTop, paintLine)
        val headers = listOf("Pos", "Beschreibung", "Menge", "Einzelpreis", "Summe")
        val cols = floatArrayOf(left, 180f, 900f, 1200f, 1500f, right)
        paintText.textSize = spToPx(13f, dpi)
        for (i in 0 until cols.size - 1) {
            canvas.drawText(headers[i], cols[i] + 8f, tableTop + 40f, paintText)
            canvas.drawLine(cols[i], tableTop, cols[i], tableTop + 50f, paintLine)
        }
        canvas.drawLine(left, tableTop + 50f, right, tableTop + 50f, paintLine)

        // Tabelle Zeilen
        var y = tableTop + 50f
        for (row in 1..25) {
            val rowH = 42f
            y += rowH
            canvas.drawLine(left, y, right, y, paintLine)
            // ein paar Beispieltexte
            drawRowText(canvas, paintText, cols, y - 12f, row)
        }

        // Fußzeile
        paintText.textSize = spToPx(12f, dpi)
        canvas.drawText("Vielen Dank für Ihren Einkauf. Zahlungsziel: 14 Tage netto.", 80f, height - 160f, paintText)

        // leichtes „Papierrauschen“
        addSubtleNoise(bmp, amount = 6)

        return bmp
    }

    private fun drawRowText(canvas: Canvas, paint: Paint, cols: FloatArray, baseline: Float, idx: Int) {
        canvas.drawText("%02d".format(idx), cols[0] + 8f, baseline, paint)
        canvas.drawText("Artikel $idx – Beispielbeschreibung", cols[1] + 8f, baseline, paint)
        canvas.drawText("1", cols[2] + 8f, baseline, paint)
        canvas.drawText("19,99 €", cols[3] + 8f, baseline, paint)
        canvas.drawText("19,99 €", cols[4] + 8f, baseline, paint)
    }

    private fun spToPx(sp: Float, dpi: Int): Float {
        // simple mapping: 1sp ~ dpi/160 px; hier direkt proportional für die Testseite
        return sp * (dpi / 160f)
    }

    private fun addSubtleNoise(bmp: Bitmap, amount: Int) {
        // sehr leichtes Luminanzrauschen für realistischere JPEG-Kompression
        val w = bmp.width
        val h = bmp.height
        val rnd = java.util.Random(1234L)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices step 17) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val delta = rnd.nextInt(amount * 2 + 1) - amount
            val rr = (r + delta).coerceIn(0, 255)
            val gg = (g + delta).coerceIn(0, 255)
            val bb = (b + delta).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
