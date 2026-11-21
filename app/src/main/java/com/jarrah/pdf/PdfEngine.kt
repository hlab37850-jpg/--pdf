package com.jarrah.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import java.io.File

object PdfEngine {

    fun cropPage(input: File, output: File, pageIndex: Int, rect: RectF) {
        PDDocument.load(input).use { doc ->
            doc.getPage(pageIndex).cropBox =
                PDRectangle(rect.left, rect.top, rect.width(), rect.height())
            doc.save(output)
        }
    }

    fun extractTextFromRegion(input: File, pageIndex: Int, rect: RectF): String {
        PDDocument.load(input).use { doc ->
            val s = PDFTextStripperByArea()
            s.addRegion("region", rect)
            s.extractRegions(doc.getPage(pageIndex))
            return s.getTextForRegion("region")
        }
    }

    fun insertBlankPage(input: File, output: File, afterPageIndex: Int) {
        PDDocument.load(input).use { doc ->
            doc.pages.insertNewPage(afterPageIndex + 1, PDPage(PDRectangle.A4))
            doc.save(output)
        }
    }
}
