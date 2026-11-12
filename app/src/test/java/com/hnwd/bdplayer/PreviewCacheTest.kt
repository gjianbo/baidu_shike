package com.hnwd.bdplayer

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val megabyte = 1024L * 1024L

    @Test
    fun loweringLimitEvictsOldestFilesAcrossBothDirectories() {
        val openedImage = cached("images/opened.jpg", 30, lastAccess = 1_000)
        val oldPdf = cached("pdf/old.pdf", 70, lastAccess = 2_000)
        val oldImage = cached("images/old.jpg", 30, lastAccess = 3_000)
        val newPdf = cached("pdf/new.pdf", 80, lastAccess = 4_000)
        val newestPdf = cached("pdf/newest.pdf", 80, lastAccess = 5_000)
        val cache = PreviewCache(folder.root)
        assertEquals(PreviewCacheLimit.DEFAULT, cache.usage().limit)
        cache.acquire(PreviewCache.Kind.IMAGE, openedImage.name)?.let(cache::release)

        val usage = cache.setLimit(PreviewCacheLimit.SMALL)

        assertFalse(oldPdf.exists())
        assertFalse(oldImage.exists())
        assertTrue(openedImage.exists())
        assertTrue(newPdf.exists())
        assertTrue(newestPdf.exists())
        assertEquals(30L * megabyte, usage.imageBytes)
        assertEquals(160L * megabyte, usage.pdfBytes)
    }

    @Test
    fun publishingDownloadTrimsAndRetainsTheCompletedFile() {
        val cache = PreviewCache(folder.root)
        cache.setLimit(PreviewCacheLimit.SMALL)
        val oldest = cached("pdf/oldest.pdf", 80, lastAccess = 1_000)
        val latest = cached("pdf/latest.pdf", 80, lastAccess = 2_000)
        val image = cached("images/existing.jpg", 20, lastAccess = 3_000)
        val temp = cache.target(PreviewCache.Kind.IMAGE)
        RandomAccessFile(temp, "rw").use { it.setLength(30L * megabyte) }

        val published = cache.publish(PreviewCache.Kind.IMAGE, "complete.jpg", temp)
        waitForRemoval(oldest)

        assertFalse(temp.exists())
        assertTrue(latest.exists())
        assertTrue(image.exists())
        assertEquals(130L * megabyte, cache.usage().totalBytes)
        assertEquals(30L * megabyte, cache.usage().inUseBytes)
        cache.release(published)
    }

    @Test
    fun exceedingLimitWhileInUseTrimsAfterFinalRelease() {
        val cache = PreviewCache(folder.root)
        val files = (1..3).map { index ->
            cached("pdf/file-$index.pdf", 75, lastAccess = index * 1_000L)
                .also { assertTrue(cache.retain(it)) }
        }
        val first = files.first()
        assertTrue(cache.retain(first))
        assertEquals(225L * megabyte, cache.setLimit(PreviewCacheLimit.SMALL).totalBytes)

        cache.release(first)
        assertEquals(225L * megabyte, cache.usage().inUseBytes)
        cache.release(first)

        waitForRemoval(first)
        assertEquals(150L * megabyte, cache.usage().totalBytes)
    }

    @Test
    fun trimmingRemovesInterruptedDownloadsAndPreservesActiveOnes() {
        val cache = PreviewCache(folder.root)
        val recent = System.currentTimeMillis()
        val abandonedImage = cached("images/image-interrupted.tmp", 20, recent)
        val abandonedPdf = cached("pdf/document-interrupted.pdf", 20, recent)
        val firstPdf = cached("pdf/first.pdf", 80)
        val secondPdf = cached("pdf/second.pdf", 80)
        val image = cached("images/preview.jpg", 30)
        val downloading = cache.target(PreviewCache.Kind.PDF)
        RandomAccessFile(downloading, "rw").use { it.setLength(5L * megabyte) }

        val usage = cache.setLimit(PreviewCacheLimit.SMALL)

        assertFalse(abandonedImage.exists())
        assertFalse(abandonedPdf.exists())
        assertTrue(downloading.exists())
        assertTrue(firstPdf.exists())
        assertTrue(secondPdf.exists())
        assertTrue(image.exists())
        assertEquals(195L * megabyte, usage.totalBytes)
        assertEquals(5L * megabyte, usage.inUseBytes)
    }

    private fun waitForRemoval(file: File) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (file.exists() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(file.exists())
    }

    @Test
    fun manualClearPreservesOpenFilesDownloadsAndOtherDirectories() {
        val cache = PreviewCache(folder.root)
        val opened = cached("images/open.jpg", 5)
        val unused = cached("pdf/unused.pdf", 10)
        val other = cached("media-addresses/stream.m3u8", 1)
        val download = cache.target(PreviewCache.Kind.PDF).apply { writeText("downloading") }
        val abandoned = cached("images/image-abandoned.tmp", 1)
        assertTrue(cache.retain(opened))

        val usage = cache.clear()

        assertTrue(opened.exists())
        assertTrue(download.exists())
        assertTrue(other.exists())
        assertFalse(unused.exists())
        assertFalse(abandoned.exists())
        assertEquals(opened.length() + download.length(), usage.totalBytes)
        assertEquals(usage.totalBytes, usage.inUseBytes)
    }

    private fun cached(path: String, megabytes: Int, lastAccess: Long = 1_000): File =
        File(folder.root, path).apply {
            parentFile?.mkdirs()
            RandomAccessFile(this, "rw").use { it.setLength(megabytes * megabyte) }
            assertTrue(setLastModified(lastAccess))
        }
}
