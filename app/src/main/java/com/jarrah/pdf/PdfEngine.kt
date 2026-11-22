package com.jarrah.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.FileOutputStream

object PdfEngine {

    fun pageCount(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    fun renderPage(file: File, pageIndex: Int, targetWidthPx: Int): Bitmap {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(pfd).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val aspect = page.height.toFloat() / page.width.toFloat()
                val h = (targetWidthPx * aspect).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(targetWidthPx, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    /**
     * rectNorm إحداثيات نسبية (0..1) مع نقطة الأصل أعلى-يسار الصفحة المعروضة.
     */
    fun cropPage(input: File, output: File, pageIndex: Int, rectNorm: RectF) {
        PDDocument.load(input).use { doc ->
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

    fun rotatePage(input: File, output: File, pageIndex: Int, deltaDegrees: Int) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            page.rotation = ((page.rotation + deltaDegrees) % 360 + 360) % 360
            doc.save(output)
        }
    }

    fun deletePage(input: File, output: File, pageIndex: Int) {
        PDDocument.load(input).use { doc ->
            if (doc.numberOfPages <= 1) throw IllegalStateException("لا يمكن حذف الصفحة الوحيدة")
            doc.removePage(pageIndex)
            doc.save(output)
        }
    }

    fun insertBlankPage(input: File, output: File, afterIndex: Int) {
        PDDocument.load(input).use { doc ->
            val newPage = PDPage(PDRectangle.A4)
            if (afterIndex < 0 || afterIndex >= doc.numberOfPages - 1) {
                doc.addPage(newPage)
            } else {
                doc.pages.insertAfter(newPage, doc.getPage(afterIndex))
            }
            doc.save(output)
        }
    }

    fun insertTablePage(input: File, output: File, afterIndex: Int, rows: Int, cols: Int) {
        PDDocument.load(input).use { doc ->
            val page = PDPage(PDRectangle.A4)
            if (afterIndex < 0 || afterIndex >= doc.numberOfPages - 1) {
                doc.addPage(page)
            } else {
                doc.pages.insertAfter(page, doc.getPage(afterIndex))
            }
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
                    cs.moveTo(margin, y)
                    cs.lineTo(margin + tableW, y)
                }
                cs.stroke()
                for (c in 0..cols) {
                    val x = margin + c * colW
                    cs.moveTo(x, startY)
                    cs.lineTo(x, startY - rows * rowH)
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
}
