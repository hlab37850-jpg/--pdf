package com.jarrah.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import java.io.File

object PdfEngine {

    /**
     * قص منطقة من صفحة (CropBox) — مناسب لقص عمود أو جدول من الصفحة.
     * @param rect المستطيل المراد إظهاره بالوحدات (points، 72 لكل بوصة).
     *             الإحداثيات تبدأ من الركن السفلي الأيسر للصفحة كما في PDF.
     */
    fun cropPage(input: File, output: File, pageIndex: Int, rect: RectF) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            page.cropBox = PDRectangle(rect.left, rect.top, rect.width(), rect.height())
            doc.save(output)
        }
    }

    /** استخراج نص من منطقة محددة (لتحليل جدول قبل القص). */
    fun extractTextFromRegion(input: File, pageIndex: Int, rect: RectF): String {
        PDDocument.load(input).use { doc ->
            val stripper = PDFTextStripperByArea()
            stripper.addRegion("region", rect)
            stripper.extractRegions(doc.getPage(pageIndex))
            return stripper.getTextForRegion("region")
        }
    }

    /**
     * إدراج صفحة فارغة بعد صفحة معينة.
     * @param afterPageIndex رقم الصفحة (0-based) التي ستُدرج الصفحة بعدها.
     *                       إذا كان -1 أو أكبر من آخر صفحة، تُضاف في النهاية.
     */
    fun insertBlankPage(input: File, output: File, afterPageIndex: Int) {
        PDDocument.load(input).use { doc ->
            val newPage = PDPage(PDRectangle.A4)
            val total = doc.numberOfPages
            when {
                afterPageIndex < 0 || afterPageIndex >= total - 1 -> {
                    doc.addPage(newPage)
                }
                else -> {
                    val refPage = doc.getPage(afterPageIndex)
                    doc.pages.insertAfter(newPage, refPage)
                }
            }
            doc.save(output)
        }
    }

    /** دمج عدة ملفات PDF في ملف واحد (يعتمد على PDFBox فقط). */
    fun mergePdfs(inputs: List<File>, output: File) {
        PDDocument().use { out ->
            val merger = com.tom_roush.pdfbox.multipdf.PDFMergerUtility()
            merger.destinationFileName = output.absolutePath
            inputs.forEach { merger.addSource(it) }
            merger.mergeDocuments(null)
        }
    }
}
