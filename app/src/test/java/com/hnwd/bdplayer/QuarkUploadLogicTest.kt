package com.hnwd.bdplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkUploadLogicTest {

    private val mb = 1024L * 1024

    @Test
    fun chunksAreAlwaysPartSize_noFiveMegabyteSpecialCase() {
        // 实测流程统一按 metadata.part_size 分片，旧文档的「<5MB 单分片」不再适用。
        assertEquals(1, QuarkUploadLogic.chunkCount(0))
        assertEquals(1, QuarkUploadLogic.chunkCount(1 * mb))
        assertEquals(2, QuarkUploadLogic.chunkCount(5 * mb))
        assertEquals(3, QuarkUploadLogic.chunkCount(10 * mb))
        assertTrue(QuarkUploadLogic.isSinglePart(4 * mb))
        assertFalse(QuarkUploadLogic.isSinglePart(4 * mb + 1))
        assertEquals(2, QuarkUploadLogic.chunkCount(10 * mb, 8 * 1024 * 1024))
    }

    @Test
    fun chunkAt_keepsRealLengthOfLastPart() {
        val single = QuarkUploadLogic.chunkAt(2 * mb, 0)
        assertEquals(0L, single.offset)
        assertEquals((2 * mb).toInt(), single.length)

        val first = QuarkUploadLogic.chunkAt(10 * mb, 0)
        val last = QuarkUploadLogic.chunkAt(10 * mb, 2)
        assertEquals(4 * mb, first.length.toLong())
        assertEquals(8 * mb, last.offset)
        assertEquals(2 * mb, last.length.toLong())
    }

    @Test
    fun progress_neverOvershootsBeforeFinish() {
        assertEquals(0, QuarkUploadLogic.progress(0, 0))
        assertEquals(0, QuarkUploadLogic.progress(0, 100))
        assertEquals(49, QuarkUploadLogic.progress(49, 100))
        assertEquals(99, QuarkUploadLogic.progress(99, 100))
        assertEquals(100, QuarkUploadLogic.progress(100, 100))
    }

    @Test
    fun formatType_fallsBackToOctetStream() {
        assertEquals("video/mp4", QuarkUploadLogic.formatType("课.MP4"))
        assertEquals("application/pdf", QuarkUploadLogic.formatType("a.pdf"))
        assertEquals("application/octet-stream", QuarkUploadLogic.formatType("a.bin"))
        assertEquals("application/octet-stream", QuarkUploadLogic.formatType("noext"))
    }

    @Test
    fun preBody_carriesDocumentedFields() {
        val body = QuarkUploadLogic.preBody("a.bin", 1234L, "0", "application/octet-stream", 999L)
        assertEquals("a.bin", body.getString("file_name"))
        assertEquals(1234L, body.getLong("size"))
        assertEquals("0", body.getString("pdir_fid"))
        assertEquals("", body.getString("dir_name"))
        assertTrue(body.getBoolean("ccp_hash_update"))
        // 多分片必须声明 parallel_upload=true，服务端据此启用 parallel multipart，
        // 第 2 片起配合 X-Oss-Hash-Ctx 才能通过（kuake_cli 实测口径）。
        assertTrue(body.getBoolean("parallel_upload"))
        assertEquals(999L, body.getLong("l_updated_at"))
        assertEquals(999L, body.getLong("l_created_at"))
        assertTrue(QuarkUploadLogic.preUrl().endsWith("/file/upload/pre?pr=ucpro&fr=pc&uc_param_str="))
    }

    @Test
    fun parsePre_readsCallbackAndPartSizeFromMetadata() {
        val json = JSONObject(
            """{"code":0,"metadata":{"part_size":4194304,"part_thread":1},
               "data":{"task_id":"T1","upload_id":"U1","obj_key":"a/b.bin","bucket":"ul-sz",
               "upload_url":"http://pds.quark.cn","fid":"F1","auth_info":"AUTH","auth_info_expried":1700,
               "finish":false,
               "callback":{"callbackUrl":"https://x.cn/cb","callbackBody":"content_hash=${'$'}{sha1}"}}}""",
        )
        val pre = QuarkUploadLogic.parsePre(json)
        assertEquals("T1", pre.taskId)
        assertEquals("U1", pre.uploadId)
        assertEquals("a/b.bin", pre.objKey)
        assertEquals("ul-sz", pre.bucket)
        assertEquals("AUTH", pre.authInfo)
        assertEquals(1700L, pre.authInfoExpired)
        assertEquals(4194304, pre.partSize)
        assertEquals(1, pre.partThread)
        assertFalse(pre.finished)
        assertEquals("https://x.cn/cb", pre.callback.getString("callbackUrl"))

        assertTrue(runCatching {
            QuarkUploadLogic.parsePre(JSONObject("""{"code":32004,"message":"too big"}"""))
        }.isFailure)
        assertTrue(runCatching {
            QuarkUploadLogic.parsePre(JSONObject("""{"code":0,"data":{}}"""))
        }.isFailure)
    }

    @Test
    fun parsePre_fallsBackToFourMegabytesWhenMetadataMissing() {
        val pre = QuarkUploadLogic.parsePre(
            JSONObject("""{"code":0,"data":{"task_id":"T","callback":{}}}"""),
        )
        assertEquals(QuarkUploadLogic.DEFAULT_PART_SIZE, pre.partSize)
        assertEquals(1, pre.partThread)
        assertTrue(pre.callback.length() == 0)
    }

    // ============ 关键修复：update/hash 与 finish 的契约 ============

    @Test
    fun updateHashBody_carriesWholeFileHashes() {
        val body = QuarkUploadLogic.updateHashBody("T1", "MD5HEX", "SHA1HEX")
        assertEquals("T1", body.getString("task_id"))
        assertEquals("MD5HEX", body.getString("md5"))
        assertEquals("SHA1HEX", body.getString("sha1"))
        assertTrue(QuarkUploadLogic.updateHashUrl().endsWith("/file/update/hash?pr=ucpro&fr=pc&uc_param_str="))
    }

    @Test
    fun parseUpdateHash_detectsFastUpload() {
        val hit = QuarkUploadLogic.parseUpdateHash(
            JSONObject("""{"code":0,"data":{"finish":true,"fid":"FID9","obj_key":"OBJ9"}}"""),
        )
        assertTrue(hit.finished)
        assertEquals("FID9", hit.fid)
        assertEquals("OBJ9", hit.objKey)

        val miss = QuarkUploadLogic.parseUpdateHash(
            JSONObject("""{"code":0,"data":{"finish":false}}"""),
        )
        assertFalse(miss.finished)
        assertEquals("", miss.fid)

        assertTrue(runCatching {
            QuarkUploadLogic.parseUpdateHash(JSONObject("""{"code":20002,"message":"expired"}"""))
        }.isFailure)
    }

    @Test
    fun finishBody_and_parseRequireFinishTrue() {
        val body = QuarkUploadLogic.finishBody("OBJ1", "T1")
        assertEquals("OBJ1", body.getString("obj_key"))
        assertEquals("T1", body.getString("task_id"))
        assertTrue(QuarkUploadLogic.finishUrl().endsWith("/file/upload/finish?pr=ucpro&fr=pc&uc_param_str="))
        assertEquals(
            "FID_FINAL",
            QuarkUploadLogic.parseFinish(
                JSONObject("""{"code":0,"data":{"finish":true,"fid":"FID_FINAL"}}"""),
            ),
        )
        assertTrue(runCatching {
            QuarkUploadLogic.parseFinish(JSONObject("""{"code":0,"data":{"finish":false}}"""))
        }.isFailure)
        assertTrue(runCatching {
            QuarkUploadLogic.parseFinish(JSONObject("""{"code":43001,"message":"complete failed"}"""))
        }.isFailure)
    }

    @Test
    fun deleteBody_usesActionTypeTwo() {
        val body = QuarkUploadLogic.deleteBody(listOf("F1", "F2"))
        assertEquals(2, body.getInt("action_type"))
        assertEquals(2, body.getJSONArray("filelist").length())
        assertEquals("F1", body.getJSONArray("filelist").getString(0))
    }

    // ============ OSS 签名与合并 XML ============

    @Test
    fun ossUrls_useDynamicHostAndKeepObjectKeyPath() {
        val url = "http://pds.quark.cn"
        val put = QuarkUploadLogic.ossPutUrl("ul-zb", "a/b.bin", "U 1", 2, url)
        assertTrue(put.startsWith("https://ul-zb.pds.quark.cn/a/b.bin?partNumber=2&uploadId="))
        assertFalse(put.contains("U 1"))
        val complete = QuarkUploadLogic.ossCompleteUrl("ul-zb", "/a/b.bin", "U1", url)
        assertEquals("https://ul-zb.pds.quark.cn/a/b.bin?uploadId=U1", complete)

        // upload_url 变化时不能写死域名；为空时回退到保底主机。
        assertEquals(
            "https://ul-zb.oss-cn-zhangjiakou.aliyuncs.com/a/b.bin?uploadId=U1",
            QuarkUploadLogic.ossCompleteUrl("ul-zb", "a/b.bin", "U1", "https://oss-cn-zhangjiakou.aliyuncs.com/"),
        )
        assertEquals("pds.quark.cn", QuarkUploadLogic.ossHost(""))
        assertEquals("pds.quark.cn", QuarkUploadLogic.ossHost("http://pds.quark.cn/"))
    }

    @Test
    fun completeXml_matchesAlistTemplateWithNewlines() {
        val xml = QuarkUploadLogic.completeXml(listOf(2 to "ETAG2", 1 to "\"ETAG1\""))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<CompleteMultipartUpload>\n" +
                "  <Part>\n    <PartNumber>1</PartNumber>\n    <ETag>\"ETAG1\"</ETag>\n  </Part>\n" +
                "  <Part>\n    <PartNumber>2</PartNumber>\n    <ETag>\"ETAG2\"</ETag>\n  </Part>\n" +
                "</CompleteMultipartUpload>",
            xml,
        )
    }

    @Test
    fun completeXml_escapesEtags() {
        val xml = QuarkUploadLogic.completeXml(listOf(1 to "a&b<c>"))
        assertTrue(xml.contains("\"a&amp;b&lt;c&gt;\""))
    }

    @Test
    fun contentMd5_matchesKnownVector() {
        assertEquals("kAFQmDzST7DWlj99KOF/cg==", QuarkUploadLogic.contentMd5("abc".toByteArray()))
        // 同一份 XML 字节算出的 MD5，必须就是合并请求实际发送的字节。
        val xml = QuarkUploadLogic.completeXml(listOf(1 to "E"))
        assertEquals(
            QuarkUploadLogic.contentMd5(xml.toByteArray(Charsets.UTF_8)),
            QuarkUploadLogic.contentMd5(xml.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun authMeta_putPart_hasEmptyContentMd5ForMultipart() {
        // 实测（20MB / 6 分片）：PUT 带分片 Content-MD5 会在 parallel multipart 下触发
        // 400 NoHashContext；按文档 §1 与 AList upPart，第二行必须为空。
        val meta = QuarkUploadLogic.authMeta(
            method = "PUT", contentMd5Value = "", contentType = "application/octet-stream",
            gmt = "Thu, 12 Nov 2026 09:35:53 GMT",
            resource = "/ul-sz/key?partNumber=1&uploadId=U1",
        )
        assertEquals(
            "PUT\n\napplication/octet-stream\nThu, 12 Nov 2026 09:35:53 GMT\n" +
                "x-oss-date:Thu, 12 Nov 2026 09:35:53 GMT\n" +
                "x-oss-user-agent:${QuarkUploadLogic.OSS_USER_AGENT}\n" +
                "/ul-sz/key?partNumber=1&uploadId=U1",
            meta,
        )
    }

    @Test
    fun authMeta_commit_putsCallbackFirstInCanonicalHeaders() {
        val meta = QuarkUploadLogic.authMeta(
            method = "POST", contentMd5Value = "XMLMD5", contentType = "application/xml",
            gmt = "GMT", callbackValue = "CB64", resource = "/ul-sz/key?uploadId=U1",
        )
        assertEquals(
            "POST\nXMLMD5\napplication/xml\nGMT\n" +
                "x-oss-callback:CB64\nx-oss-date:GMT\n" +
                "x-oss-user-agent:${QuarkUploadLogic.OSS_USER_AGENT}\n" +
                "/ul-sz/key?uploadId=U1",
            meta,
        )
    }

    @Test
    fun authMeta_putPartWithHashCtx_putsItFirstInCanonicalHeaders() {
        val meta = QuarkUploadLogic.authMeta(
            method = "PUT", contentMd5Value = "", contentType = "application/octet-stream",
            gmt = "GMT", hashCtxValue = "CTX64",
            resource = "/ul-sz/key?partNumber=2&uploadId=U1",
        )
        assertEquals(
            "PUT\n\napplication/octet-stream\nGMT\n" +
                "X-Oss-Hash-Ctx:CTX64\nx-oss-date:GMT\n" +
                "x-oss-user-agent:${QuarkUploadLogic.OSS_USER_AGENT}\n" +
                "/ul-sz/key?partNumber=2&uploadId=U1",
            meta,
        )
    }

    @Test
    fun resources_keepBucketAndSortedQuery() {
        assertEquals(
            "/ul-sz/a/b.bin?partNumber=2&uploadId=U+1",
            QuarkUploadLogic.putResource("ul-sz", "a/b.bin", "U 1", 2),
        )
        assertEquals(
            "/ul-sz/a/b.bin?uploadId=U1",
            QuarkUploadLogic.commitResource("ul-sz", "/a/b.bin", "U1"),
        )
    }

    @Test
    fun callbackBase64_isCompactJson() {
        val cb = JSONObject("""{"callbackUrl":"https://x.cn/cb","callbackBody":"bucket=abc"}""")
        val decoded = String(java.util.Base64.getDecoder().decode(QuarkUploadLogic.callbackBase64(cb)))
        val back = JSONObject(decoded)
        assertEquals("https://x.cn/cb", back.getString("callbackUrl"))
        assertEquals("bucket=abc", back.getString("callbackBody"))
    }

    @Test
    fun uploadError_mapsKnownCodes() {
        assertTrue(QuarkUploadLogic.uploadError(32004, "").contains("上限"))
        assertTrue(QuarkUploadLogic.uploadError(20002, "").contains("失效"))
        assertTrue(QuarkUploadLogic.uploadError(14001, "").contains("签名"))
        assertTrue(QuarkUploadLogic.uploadError(43001, "").contains("合并"))
        assertTrue(QuarkUploadLogic.uploadError(9999, "boom").contains("9999"))
    }
}
