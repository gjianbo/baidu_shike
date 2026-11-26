package com.hnwd.bdplayer

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ForwardServerClientTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun resolvePostsUrlAndDoesNotFollowRedirects() {
        val bodyRef = AtomicReference<String>()
        val unexpected = AtomicInteger()
        LocalServer { request ->
            unexpected.incrementAndGet()
            request.send(200)
        }.use { other ->
            LocalServer { request ->
                when (request.path) {
                    "/prefix/resolve" -> {
                        bodyRef.set(request.body.toString(Charsets.UTF_8))
                        request.send(308, headers = mapOf("Location" to "${other.url}/capture"))
                    }
                    else -> request.send(200)
                }
            }.use { server ->
                val client = ForwardServerClient(
                    ForwardServerConfig("${server.url}/prefix"),
                    OkHttpClient.Builder().followRedirects(true).build(),
                )
                assertTrue(assertThrows(IllegalStateException::class.java) {
                    client.parse("https://share.example/video")
                }.message.orEmpty().contains("HTTP 308"))
                assertTrue(bodyRef.get().contains("https://share.example/video"))
                assertEquals(0, unexpected.get())
            }
        }
    }

    @Test
    fun checkServiceRequiresResolveResponseShape() {
        val body = AtomicReference<String>()
        LocalServer { request ->
            request.send(200, body.get().toByteArray(), mapOf("Content-Type" to "application/json"))
        }.use { server ->
            val client = ForwardServerClient(ForwardServerConfig(server.url))
            body.set("""{"status":"ok","url":"https://media.example/video.mp4"}""")
            client.checkService()
            body.set("""{"status":"error","msg":"fixture"}""")
            client.checkService()
            for (invalid in listOf(
                """{"status":"ok"}""",
                """{"status":"ok","url":"file:///video.mp4"}""",
                """{"status":"error"}""",
                """{"status":"error","msg":42}""",
                """{"status":"unknown"}""",
            )) {
                body.set(invalid)
                assertThrows(IllegalStateException::class.java) { client.checkService() }
            }
        }
    }

    @Test
    fun resolveRejectsOversizedDecodedResponse() {
        val largeJson = """{"status":"error","msg":"${"a".repeat(130 * 1024)}"}"""
        val compressed = java.io.ByteArrayOutputStream().apply {
            java.util.zip.GZIPOutputStream(this).use { it.write(largeJson.toByteArray()) }
        }.toByteArray()
        LocalServer { request ->
            request.send(200, compressed, mapOf("Content-Encoding" to "gzip"))
        }.use { server ->
            val client = ForwardServerClient(ForwardServerConfig(server.url))
            assertTrue(assertThrows(IllegalStateException::class.java) {
                client.checkService()
            }.message.orEmpty().contains("响应内容过大"))
        }
    }

    @Test
    fun mediaRedirectRebuildsHeadersAndSavesUnmodifiedBytes() {
        val payload = bmff("isom") + ByteArray(6_000) { (it % 256).toByte() }
        val requestHosts = mutableListOf<String>()
        val injectedClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requestHosts += request.url.host
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            if (request.url.host == "video.twimg.com") {
                assertEquals("https://x.com/", request.header("Referer"))
                response.code(302).message("Found").header("Location", "https://unknown.example/video")
                    .header("Set-Cookie", "injected=value").body(ByteArray(0).toResponseBody()).build()
            } else {
                assertNull(request.header("Referer"))
                response.code(200).message("OK").header("Content-Type", "application/octet-stream")
                    .body(payload.toResponseBody()).build()
            }
        }.build()
        val client = ForwardServerClient(ForwardServerConfig("https://parser.example"), injectedClient)
        val file = folder.root.resolve("video.mp4")
        client.download("https://video.twimg.com/direct", file) {}
        assertEquals(listOf("video.twimg.com", "unknown.example"), requestHosts)
        assertArrayEquals(payload, file.readBytes())
        assertFalse(folder.root.resolve("video.mp4.part").exists())
    }

    @Test
    fun videoProbeRequestsOnlyTheHeaderAndRejectsNonVideoResponses() {
        val injectedClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("bytes=0-4095", request.header("Range"))
            assertNull(request.header("Cookie"))
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .header("Content-Type", "video/mp4").message("Fixture")
            when (request.url.encodedPath) {
                "/partial" -> response.code(206).header("Content-Range", "bytes 0-15/8192")
                    .body(bmff("isom").toResponseBody()).build()
                "/full" -> response.code(200).body(bmff("isom").toResponseBody()).build()
                "/forbidden" -> response.code(403).body(ByteArray(0).toResponseBody()).build()
                else -> response.code(200).body("<html>Error</html>".toByteArray().toResponseBody()).build()
            }
        }.build()
        val client = ForwardServerClient(ForwardServerConfig("https://parser.example"), injectedClient)
        client.probeVideo("https://media.example/partial")
        client.probeVideo("https://media.example/full")
        assertThrows(IllegalStateException::class.java) { client.probeVideo("https://media.example/html") }
        assertTrue(assertThrows(IllegalStateException::class.java) {
            client.probeVideo("https://media.example/forbidden")
        }.message.orEmpty().contains("HTTP 403"))
    }

    @Test
    fun rejectsDisguisedOrTruncatedMediaWithoutPromotingFile() {
        LocalServer { request ->
            val video = mapOf("Content-Type" to "video/mp4")
            when (request.path) {
                "/truncated" -> request.send(200, bmff("isom"), video, declaredLength = 10_000)
                else -> request.send(200, "<html>Error</html>".toByteArray(), video)
            }
        }.use { server ->
            val client = ForwardServerClient(ForwardServerConfig(server.url))
            val file = folder.root.resolve("video.mp4")
            assertThrows(IllegalStateException::class.java) { client.download("${server.url}/html", file) {} }
            assertThrows(java.io.IOException::class.java) { client.download("${server.url}/truncated", file) {} }
            assertFalse(file.exists())
            assertFalse(folder.root.resolve("video.mp4.part").exists())
        }
    }

    @Test
    fun cancelledDownloadWithoutContentLengthDeletesPartialFile() {
        val payload = bmff("isom") + ByteArray(256 * 1024)
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : okhttp3.ResponseBody() {
                private val input = okio.Buffer().write(payload)
                override fun contentLength() = -1L
                override fun contentType(): okhttp3.MediaType? = null
                override fun source(): okio.BufferedSource = input
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val client = ForwardServerClient(ForwardServerConfig("https://parser.example"), transport)
        val file = folder.root.resolve("video.mp4")
        val partial = folder.root.resolve("video.mp4.part")
        val error = assertThrows(IllegalStateException::class.java) {
            client.download("https://media.example/video", file, checkActive = {
                check(partial.length() == 0L) { "cancelled" }
            }) { throw AssertionError("Unknown-length transfer should not report a percentage") }
        }
        assertEquals("cancelled", error.message)
        assertFalse(file.exists())
        assertFalse(partial.exists())
    }

    private class LocalServer(handler: (LocalRequest) -> Unit) : Closeable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        private val worker = thread(isDaemon = true, name = "forward-test-http") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (error: SocketException) {
                    if (!server.isClosed) throw error
                    break
                }
                socket.use { handler(LocalRequest(it)) }
            }
        }
        val url: String get() = "http://127.0.0.1:${server.localPort}"
        override fun close() {
            server.close()
            worker.join(1_000)
        }
    }

    private class LocalRequest(private val socket: Socket) {
        private val source = socket.getInputStream().source().buffer()
        val path = source.readUtf8LineStrict().split(' ')[1].substringBefore('?')
        val headers = buildMap {
            while (true) {
                val line = source.readUtf8LineStrict()
                if (line.isEmpty()) break
                put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
            }
        }
        val body = source.readByteArray(headers["content-length"]?.toLong() ?: 0L)

        fun send(
            code: Int,
            payload: ByteArray = ByteArray(0),
            headers: Map<String, String> = emptyMap(),
            declaredLength: Int = payload.size,
        ) {
            val output = socket.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 $code Test\r\nConnection: close\r\nContent-Length: $declaredLength\r\n")
                for ((key, value) in headers) append("$key: $value\r\n")
                append("\r\n")
            }
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(payload)
            output.flush()
        }
    }
}
