package com.hnwd.bdplayer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

internal data class PdfOutlineEntry(val title: String, val level: Int, val page: Int?)

internal fun readPdfOutline(context: Context, file: File): List<PdfOutlineEntry> {
    PDFBoxResourceLoader.init(context.applicationContext)
    val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
    return PDDocument.load(file, memory).use { document ->
        val first = document.documentCatalog.documentOutline?.firstChild ?: return emptyList()
        val pageNumbers = document.pages.withIndex().associate { it.value.cosObject to it.index }
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        val pending = ArrayDeque<Pair<PDOutlineItem, Int>>()
        pending.addLast(first to 0)
        val entries = mutableListOf<PdfOutlineEntry>()

        while (pending.isNotEmpty() && seen.size < 4096) {
            val (item, level) = pending.removeLast()
            if (!seen.add(item.cosObject)) continue
            val page = runCatching { item.findDestinationPage(document)?.cosObject }
                .getOrNull()?.let(pageNumbers::get)
            val child = item.firstChild
            val title = item.title?.trim().orEmpty()
            if (title.isNotEmpty() && (page != null || child != null)) {
                entries.add(PdfOutlineEntry(title, level, page))
            }
            item.nextSibling?.let { pending.addLast(it to level) }
            child?.let { pending.addLast(it to (level + 1)) }
        }
        entries.takeIf { list -> list.any { it.page != null } }.orEmpty()
    }
}
