package com.hnwd.bdplayer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectorUploadSourceTest {
    @get:Rule val temp = TemporaryFolder()

    private val account = "acct_test__v"

    private fun video(cache: File, accountDirectory: String = account, job: String = "job-123") =
        File(cache, "collector/$accountDirectory/$job/video.mp4").apply {
            parentFile!!.mkdirs()
            writeText("video")
        }

    @Test fun deletesOnlyTheOwnedFileAndItsEmptyJobDirectory() {
        val cache = temp.newFolder("cache")
        val source = video(cache)
        val nextJob = video(cache, job = "job-456")
        CollectorUploadSource.validate(cache, account, source).delete()
        assertFalse(source.exists())
        assertFalse(source.parentFile!!.exists())
        assertTrue(nextJob.isFile)
    }

    @Test fun neverRecursivelyDeletesOtherFiles() {
        val cache = temp.newFolder("cache")
        val source = video(cache)
        val neighbor = File(source.parentFile, "unowned.mp4").apply { writeText("keep") }
        CollectorUploadSource.validate(cache, account, source).delete()
        assertFalse(source.exists())
        assertTrue(neighbor.isFile)
    }

    @Test fun rejectsOtherAccountsPathsAndDirectories() {
        val cache = temp.newFolder("cache")
        val source = video(cache)
        val wrongAccount = video(cache, "other")
        val wrongJob = video(cache, job = "other")
        val outside = temp.newFile("outside.mp4")
        val folder = File(source.parentFile, "folder.mp4").apply { mkdir() }
        for (candidate in listOf(wrongAccount, wrongJob, outside, folder)) {
            assertTrue(runCatching { CollectorUploadSource.validate(cache, account, candidate) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(candidate.exists())
        }
    }

    @Test fun doesNotFollowSymlinksBeforeOrAfterHandoff() {
        val cache = temp.newFolder("cache")
        val source = video(cache)
        val outside = temp.newFile("outside.mp4")
        val owned = CollectorUploadSource.validate(cache, account, source)
        assertTrue(source.delete())
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        assertTrue(runCatching { CollectorUploadSource.validate(cache, account, source) }
            .exceptionOrNull() is IllegalArgumentException)
        owned.delete()
        assertTrue(Files.isSymbolicLink(source.toPath()))
        assertTrue(outside.isFile)
    }
}
