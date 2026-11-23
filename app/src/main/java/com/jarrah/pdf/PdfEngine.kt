package com.jarrah.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

object PdfEngine {

    private fun load(file: File): PDDocument =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

    private fun loadWithPassword(file: File, password: String): PDDocument =
        PDDocument.load(file, password, MemoryUsageSetting.setupTempFileOnly())

    fun pageCount(file: File): Int = load(file).use { it.numberOfPages }

    fun isEncrypted(file: File): Boolean = try {
        PDDocument.load(file).use { false }
    } catch (_: InvalidPasswordException) { true } catch (_: Exception) { false }

    fun tryOpenWithPassword(file: File, password: String): Boolean = try {
        PDDocument.load(file, password).use { true }
    } catch (_: Exception) { false }

    fun decryptToTemp(input: File, password: String, output: File) {
        loadWithPassword(input, password).use { it.save(output) }
    }

    fun renderPage(renderer: PdfRenderer, pageIndex: Int, targetWidthPx: Int): Bitmap {
        renderer.openPage(pageIndex).use { page ->
            val aspect = page.height.toFloat() / page.width.toFloat()
            val h = (targetWidthPx * aspect).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetWidthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    fun renderThumb(renderer: PdfRenderer, pageIndex: Int, targetWidthPx: Int = 160): Bitmap {
        renderer.openPage(pageIndex).use { page ->
            val aspect = page.height.toFloat() / page.width.toFloat()
            val h = (targetWidthPx * aspect).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetWidthPx, h, Bitmap.Config.RGB_565)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    fun cropPage(input: File, output: File, pageIndex: Int, rectNorm: RectF) {
        load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val box = page.cropBox
            val left = box.lowerLeftX + rectNorm.left * box.width
            val right = box.lowerLeftX + rectNorm.right * box.width
            val topY = box.upperRightY - rectNorm.top * box.height
            val bottomY = box.upperRightY - rectNorm.bottom * box.height
            page.cropBox = PDRectangle(left, bottomY, right - left, topY - bottomY)
            doc.save(output)
        }
    }

    fun rotatePage(input: File, output: File, pageIndex: Int, delta: Int) {
        load(input).use { doc ->
            val p = doc.getPage(pageIndex)
            p.rotation = ((p.rotation + delta) % 360 + 360) % 360
            doc.save(output)
        }
    }

    fun deletePage(input: File, output: File, pageIndex: Int) {
        load(input).use { doc ->
            if (doc.numberOfPages <= 1) throw IllegalStateException("لا يمكن حذف الصفحة الوحيدة")
            doc.removePage(pageIndex)
            doc.save(output)
        }
    }

    fun insertBlankPage(input: File, output: File, afterIndex: Int) {
        load(input).use { doc ->
            val newPage = PDPage(PDRectangle.A4)
            if (afterIndex < 0 || afterIndex >= doc.numberOfPages - 1) doc.addPage(newPage)
            else doc.pages.insertAfter(newPage, doc.getPage(afterIndex))
            doc.save(output)
        }
    }

    fun insertTablePage(input: File, output: File, afterIndex: Int, rows: Int, cols: Int) {
        load(input).use { doc ->
            val page = PDPage(PDRectangle.A4)
            if (afterIndex < 0 || afterIndex >= doc.numberOfPages - 1) doc.addPage(page)
            else doc.pages.insertAfter(page, doc.getPage(afterIndex))
            val media = page.mediaBox
            PDPageContentStream(doc, page).use { cs ->
                cs.setStrokingColor(0, 0, 0)
                cs.setLineWidth(1f)
                val margin = 40f
                val tableW = media.width - 2 * margin
                val rowH = 24f
                val colW = tableW / cols
                val startY = media.height - margin
                for (r in 0..rows) {
                    val y = startY - r * rowH
                    cs.moveTo(margin, y); cs.lineTo(margin + tableW, y)
                }
                cs.stroke()
                for (c in 0..cols) {
                    val x = margin + c * colW
                    cs.moveTo(x, startY); cs.lineTo(x, startY - rows * rowH)
                }
                cs.stroke()
            }
            doc.save(output)
        }
    }

    fun mergePdfs(inputs: List<File>, output: File) {
        FileOutputStream(output).use { out ->
            val merger = PDFMergerUtility()
            merger.destinationStream = out
            inputs.forEach { merger.addSource(it) }
            merger.mergeDocuments(null)
        }
    }

    fun searchPages(input: File, query: String): List<Int> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return load(input).use { doc ->
            val stripper = PDFTextStripper()
            val result = mutableListOf<Int>()
            for (i in 0 until doc.numberOfPages) {
                stripper.startPage = i + 1
                stripper.endPage = i + 1
                try { if (stripper.getText(doc).lowercase().contains(q)) result.add(i) } catch (_: Exception) { }
            }
            result
        }
    }

    fun splitPdf(input: File, output: File, start: Int, end: Int) {
        load(input).use { src ->
            PDDocument().use { dst ->
                for (i in start..end) if (i in 0 until src.numberOfPages) dst.importPage(src.getPage(i))
                if (dst.numberOfPages == 0) throw IllegalStateException("نطاق غير صالح")
                dst.save(output)
            }
        }
    }

    fun extractPages(input: File, output: File, pages: List<Int>) {
        load(input).use { src ->
            PDDocument().use { dst ->
                for (p in pages) if (p in 0 until src.numberOfPages) dst.importPage(src.getPage(p))
                if (dst.numberOfPages == 0) throw IllegalStateException("لا صفحات صالحة")
                dst.save(output)
            }
        }
    }

    fun reorderPages(input: File, output: File, newOrder: List<Int>) {
        load(input).use { doc ->
            val pages = newOrder.map { doc.getPage(it) }.toMutableList()
            while (doc.numberOfPages > 0) doc.removePage(0)
            pages.forEach { doc.addPage(it) }
            doc.save(output)
        }
    }

    fun compressPdf(input: File, output: File, jpegQuality: Float = 0.5f) {
        load(input).use { doc ->
            for (page in doc.pages) {
                val res = page.resources ?: continue
                val names = res.xObjectNames.toList()
                for (name in names) {
                    try {
                        val xobj = res.getXObject(name)
                        if (xobj is PDImageXObject && xobj.width > 150 && xobj.height > 150) {
                            val bi = xobj.image ?: continue
                            res.put(name, JPEGFactory.createFromImage(doc, bi, jpegQuality))
                        }
                    } catch (_: Exception) { }
                }
            }
            doc.save(output)
        }
    }

    /**
     * استبدال منطقة نصية: مستطيل أبيض + النص الجديد (يدعم العربية).
     */
    fun replaceTextRegion(
        input: File, output: File, pageIndex: Int, rectNorm: RectF,
        newText: String, fontSize: Float, textColor: Int = 0xFF000000.toInt()
    ) {
        load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val box = page.mediaBox
            val left = box.lowerLeftX + rectNorm.left * box.width
            val right = box.lowerLeftX + rectNorm.right * box.width
            val topY = box.upperRightY - rectNorm.top * box.height
            val bottomY = box.upperRightY - rectNorm.bottom * box.height

            val fontFile = ArabicHelper.findArabicFont()
            val font = if (fontFile != null) {
                try { PDType0Font.load(doc, fontFile) } catch (_: Exception) { null }
            } else null

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.setNonStrokingColor(255, 255, 255)
                cs.addRect(left, bottomY, right - left, topY - bottomY)
                cs.fill()

                val r = android.graphics.Color.red(textColor) / 255f
                val g = android.graphics.Color.green(textColor) / 255f
                val b = android.graphics.Color.blue(textColor) / 255f
                cs.setNonStrokingColor(r, g, b)

                cs.beginText()
                if (font != null) cs.setFont(font, fontSize)
                else cs.setFont(PDType1Font.HELVETICA, fontSize)
                cs.setLeading(fontSize * 1.2f)

                var y = topY - fontSize
                cs.newLineAtOffset(left + 3f, y)
                val lines = newText.split('\n')
                val isRtl = newText.any { it in '\u0600'..'\u06FF' }
                for ((i, line) in lines.withIndex()) {
                    if (y < bottomY) break
                    val prepared = if (font != null) ArabicHelper.prepareForPdf(line) else line
                    try {
                        cs.showText(prepared)
                    } catch (_: Exception) {
                        try { cs.showText(line.replace(Regex("[^\\x00-\\x7F]"), "?")) } catch (_: Exception) { }
                    }
                    if (i < lines.size - 1) {
                        cs.newLine()
                        y -= fontSize * 1.2f
                    }
                }
                cs.endText()
            }
            doc.save(output)
        }
    }

    /** إدراج Bitmap (توقيع أو صورة) داخل مستطيل محدد. */
    fun addBitmapOnPage(input: File, output: File, pageIndex: Int, rectNorm: RectF, bitmap: Bitmap) {
        load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val box = page.mediaBox
            val left = box.lowerLeftX + rectNorm.left * box.width
            val right = box.lowerLeftX + rectNorm.right * box.width
            val topY = box.upperRightY - rectNorm.top * box.height
            val bottomY = box.upperRightY - rectNorm.bottom * box.height
            val img = LosslessFactory.createFromImage(doc, bitmap)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.drawImage(img, left, bottomY, right - left, topY - bottomY)
            }
            doc.save(output)
        }
    }

    fun addImageFileOnPage(input: File, output: File, pageIndex: Int, rectNorm: RectF, imageFile: File) {
        val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw IllegalStateException("فشل تحميل الصورة")
        try { addBitmapOnPage(input, output, pageIndex, rectNorm, bmp) }
        finally { bmp.recycle() }
    }

    /** تحويل صورة إلى ملف PDF بصفحة واحدة. */
    fun imageToPdf(imageFile: File, output: File) {
        val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw IllegalStateException("فشل تحميل الصورة")
        try {
            PDDocument().use { doc ->
                val w = bmp.width.toFloat().coerceAtLeast(100f)
                val h = bmp.height.toFloat().coerceAtLeast(100f)
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                val img = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, 0f, 0f, w, h)
                }
                doc.save(output)
            }
        } finally { bmp.recycle() }
    }

    /** حماية الملف بكلمة مرور (AES-256). */
    fun encryptPdf(input: File, output: File, password: String) {
        load(input).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 256
            doc.protect(policy)
            doc.save(output)
        }
    }
}
