package com.hnwd.bdplayer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class ExtensionReleaseTest {
    private val source = ExtensionReleaseSource("owner/extension", "extension-arm64.apk")

    @Test fun acceptsOnlyThePublishedAssetAndBuildsItsTagUrl() {
        val json = JSONObject(
            """{"tag_name":"v0.1.2","draft":false,"prerelease":false,"assets":[
                {"name":"app.apk","state":"uploaded","size":300},
                {"name":"extension-arm64.apk","state":"uploaded","size":4096,
                 "browser_download_url":"https://invalid.example/other.apk"}
            ]}""",
        )
        assertEquals(ExtensionRelease("0.1.2", 4096,
            "https://github.com/owner/extension/releases/download/v0.1.2/extension-arm64.apk"), source.findAsset(json))
        json.put("prerelease", true)
        assertNull(source.findAsset(json))
        assertNull(source.asset("v0.1.2-beta", 4096))
    }

    @Test fun rateLimitFallsBackToLatestPageAndReadsTotalLengthFromRange() {
        val seen = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen.add(request.url.toString())
            val result = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .body(byteArrayOf(1).toResponseBody("application/octet-stream".toMediaType()))
            when (seen.size) {
                1 -> result.code(403).message("Rate limit").build()
                2 -> {
                    assertEquals("HEAD", request.method)
                    result.request(request.newBuilder()
                        .url("https://github.com/owner/extension/releases/tag/v0.1.2").build())
                        .code(200).message("OK").build()
                }
                else -> {
                    assertEquals("bytes=0-0", request.header("Range"))
                    result.code(206).message("Partial Content").header("Content-Range", "bytes 0-0/4096").build()
                }
            }
        }.build()
        assertEquals(4096L, ExtensionReleaseClient(http).latest(source).bytes)
        assertEquals(3, seen.size)
    }

    @Test fun missingAssetInTheApiDoesNotTriggerThePageFallback() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"tag_name":"v0.1.2","assets":[]}""".toResponseBody()).build()
        }.build()
        assertThrows(IllegalStateException::class.java) { ExtensionReleaseClient(http).latest(source) }
        assertEquals(1, calls)
    }

    @Test fun cancelledLookupDoesNotMakeAnyNetworkRequests() {
        val http = OkHttpClient.Builder().addInterceptor { throw AssertionError("Unexpected network request") }.build()
        val requests = ExtensionReleaseClient.Requests().apply { cancel() }
        assertThrows(IOException::class.java) { ExtensionReleaseClient(http).latest(source, requests) }
    }

    @Test fun rejectsDownloadWithUnexpectedLength() {
        val release = requireNotNull(source.asset("v0.1.2", 2))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(byteArrayOf(1).toResponseBody()).build()
        }.build()
        val file = File.createTempFile("extension-download", ".apk")
        try {
            assertThrows(IllegalStateException::class.java) {
                ExtensionReleaseClient(http).download(release, file) {}
            }
            assertEquals(0L, file.length())
        } finally {
            file.delete()
        }
    }
    @Test fun resumesPartialDownloadWithMatchingContentRange() {
        assertResume("bytes 2-3/4", "cd", 206, "abcd")
    }

    @Test fun restartsWhenServerIgnoresRange() {
        assertResume(null, "wxyz", 200, "wxyz")
    }

    @Test fun restartsOnceWhenRangeIsRejectedOrInvalid() {
        for (rangeResponse in listOf(416, 206)) {
            val seen = mutableListOf<String?>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val range = chain.request().header("Range")
                seen += range
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (range == null) 200 else rangeResponse).message("test")
                    .header("Content-Range", "bytes 1-2/4")
                    .body(if (range == null) "wxyz".toResponseBody() else "cd".toResponseBody()).build()
            }.build()
            val file = File.createTempFile("extension-download", ".apk")
            try {
                file.writeText("ab")
                val release = requireNotNull(source.asset("v0.1.2", 4))
                ExtensionReleaseClient(http).download(release, file) {}
                assertEquals(listOf("bytes=2-", null), seen)
                assertEquals("wxyz", file.readText())
            } finally {
                file.delete()
            }
        }
    }

    private fun assertResume(range: String?, body: String, code: Int, expected: String) {
        val release = requireNotNull(source.asset("v0.1.2", 4))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=2-", chain.request().header("Range"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").apply { if (range != null) header("Content-Range", range) }
                .body(body.toResponseBody()).build()
        }.build()
        val file = File.createTempFile("extension-download", ".apk")
        try {
            file.writeText("ab")
            ExtensionReleaseClient(http).download(release, file) {}
            assertEquals(expected, file.readText())
        } finally {
            file.delete()
        }
    }

}
