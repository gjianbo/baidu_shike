package com.hnwd.bdplayer

import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网盘响应解析：[PanApi] 里的 parse* 一族。
 *
 * 这是最容易被接口悄悄改坏的一层 —— 字段改名、多包一层、空值变成字符串 "null"，
 * 都不会报错，只会让列表变空或条目错位。用固定 fixture 把"现在认哪些形状"钉住。
 *
 * 注意：fixture 里的 path 一律用明文（不以 %2F 开头），这样 [PanApi.readablePath]
 * 不会走到 `Uri.decode`，纯 JVM 就能跑。百分号编码路径的解码需要 Android 运行时。
 */
class PanApiParsingTest {

    private fun json(text: String) = JSONObject(text)

    private fun entry(
        gid: String = "1001",
        messageId: String = "m1",
        owner: String = "2002",
        fileId: String = "f1",
        name: String = "a.mp3",
        path: String = "/课程/a.mp3",
        directory: Boolean = false,
        origin: EntryOrigin = EntryOrigin.GROUP,
    ) = PanEntry(
        gid = gid, messageId = messageId, owner = owner, fileId = fileId, name = name,
        path = path, size = "", directory = directory, origin = origin,
    )

    // ===================== 我的网盘：/api/list =====================

    @Test
    fun `网盘列表跳过缺 id 或名字的条目`() {
        val payload = json(
            """
            {"list":[
              {"fs_id":"111","server_filename":"a.mp3","path":"/课程/a.mp3","size":"2048","isdir":0,
               "md5":"abc","thumb":{"url3":"https://pan.baidu.com/thumb/x?size=w200","url1":"https://pan.baidu.com/thumb/x?size=w100"}},
              {"fs_id":"222","server_filename":"课程","path":"/课程","size":"0","isdir":1},
              {"server_filename":"no-id.mp3","path":"/x"},
              {"fs_id":"333","path":"/没有名字"}
            ]}
            """.trimIndent(),
        )

        val entries = PanApi.parseDiskList(payload)

        assertEquals(2, entries.size)
        assertEquals(listOf("111", "222"), entries.map { it.fileId })
        assertEquals("/课程/a.mp3", entries[0].path)
        assertEquals("2048", entries[0].size)
        assertTrue(entries[1].directory)
        // origin=DISK 且 gid 为空 -> 百度个人网盘
        assertEquals(EntryOrigin.DISK, entries[0].origin)
        assertEquals(SourceProvider.BAIDU, entries[0].provider)
    }

    @Test
    fun `缩略图取 url3 并把 size 换成大图`() {
        val payload = json(
            """
            {"list":[{"fs_id":"1","server_filename":"a.jpg","path":"/a.jpg","isdir":0,
              "thumb":{"url3":"https://pan.baidu.com/thumb/x?fid=1&size=w200"}}]}
            """.trimIndent(),
        )

        assertEquals(
            "https://pan.baidu.com/thumb/x?fid=1&size=c2560_u2560",
            PanApi.parseDiskList(payload).single().previewUrl,
        )
    }

    @Test
    fun `缩略图只认 https 地址`() {
        val payload = json(
            """
            {"list":[{"fs_id":"1","server_filename":"a.jpg","path":"/a.jpg","isdir":0,
              "thumb":{"url1":"http://pan.baidu.com/thumb/x"},"thumbnail":"https://pan.baidu.com/t2"}]}
            """.trimIndent(),
        )

        // http 候选被跳过，退到 thumbnail
        assertTrue(PanApi.parseDiskList(payload).single().previewUrl.startsWith("https://pan.baidu.com/t2"))
    }

    @Test
    fun `群文件把 thumb 序列化成字符串时也能解析`() {
        // 群文件接口有时把对象再序列化一层，previewUrlOf 需要兼容
        val payload = json(
            """
            {"list":[{"fs_id":"1","server_filename":"a.jpg","path":"/a.jpg","isdir":0,
              "thumb":"{\"url3\":\"https://pan.baidu.com/thumb/deep\"}"}]}
            """.trimIndent(),
        )

        assertTrue(PanApi.parseDiskList(payload).single().previewUrl.startsWith("https://pan.baidu.com/thumb/deep"))
    }

    // ===================== 我的网盘：搜索 =====================

    @Test
    fun `网盘搜索的父目录按路径回推`() {
        val payload = json(
            """
            {"list":[
              {"fs_id":"1","server_filename":"a.mp3","path":"/课程/a.mp3","isdir":0},
              {"fs_id":"2","server_filename":"root.mp3","path":"/root.mp3","isdir":0}
            ]}
            """.trimIndent(),
        )

        val hits = PanApi.parseDiskSearch(payload)

        assertEquals(listOf("/课程", "/"), hits.map { it.parentPath })
        assertEquals(listOf("1", "2"), hits.map { it.shareFsId })
    }

    @Test
    fun `网盘列表的 has_more 兼容布尔与数字两种写法`() {
        assertTrue(PanApi.hasMore(json("""{"has_more":true}""")))
        assertTrue(PanApi.hasMore(json("""{"has_more":1}""")))
        assertFalse(PanApi.hasMore(json("""{"has_more":false}""")))
        assertFalse(PanApi.hasMore(json("""{}""")))
    }

    @Test
    fun `满一页即认为还有下一页`() {
        val payload = json("""{"list":[{"fs_id":"1"},{"fs_id":"2"}]}""")

        assertTrue(PanApi.diskHasMore(payload, num = 2))
        assertFalse(PanApi.diskHasMore(payload, num = 3))
    }

    // ===================== 群组：搜索 =====================

    @Test
    fun `群组搜索只保留属于当前 gid 的命中`() {
        val payload = json(
            """
            {"result":[
              {"fsid":"11","server_filename":"第1讲.mp3","path":"/课程/第1讲.mp3","parent_path":"/课程",
               "is_dir":0,"msgId":"m","uk":"u","size":"100","share_fs_id":"sf"},
              {"fsid":"12","groupId":"999","server_filename":"别处.mp3","path":"/x/别处.mp3","parent_path":"/x"},
              {"server_filename":"没 id.mp3","path":"/y.mp3","parent_path":"/"},
              {"fsid":"13","server_filename":"","path":"/z.mp3","parent_path":"/"}
            ]}
            """.trimIndent(),
        )

        val hits = PanApi.parseSearch(payload, gid = "1001")

        assertEquals(1, hits.size)
        val hit = hits.single()
        assertEquals("11", hit.entry.fileId)
        assertEquals("1001", hit.entry.gid)
        assertEquals("/课程", hit.parentPath)
        assertEquals("sf", hit.shareFsId)
        assertEquals("m", hit.entry.messageId)
        assertEquals("u", hit.entry.owner)
        assertFalse(hit.entry.directory)
    }

    @Test
    fun `群组搜索缺 parent_path 时按 path 回推`() {
        val payload = json(
            """{"result":[{"fsid":"11","server_filename":"a.mp3","path":"/课程/a.mp3"}]}""",
        )

        assertEquals("/课程", PanApi.parseSearch(payload, gid = "1").single().parentPath)
    }

    // ===================== 群组：目录 =====================

    @Test
    fun `群组根目录从 msg_list 展开文件`() {
        val payload = json(
            """
            {"records":{"msg_list":[{"msg_id":"m1","uk":"u1","file_list":[
              {"fs_id":"1","server_filename":"a.mp3","path":"/a.mp3","isdir":"0","size":"5"},
              {"fs_id":"2","server_filename":"子目录","path":"/子目录","isdir":"1"}
            ]}]}}
            """.trimIndent(),
        )

        val entries = PanApi.parseRoots(payload, gid = "1001")

        assertEquals(listOf("1", "2"), entries.map { it.fileId })
        assertEquals(listOf("m1", "m1"), entries.map { it.messageId })
        assertEquals(listOf("u1", "u1"), entries.map { it.owner })
        assertEquals("5", entries[0].size)
        assertTrue(entries[1].directory)
    }

    @Test
    fun `联系人目录合并三种文件数组并按 fs_id 去重`() {
        val payload = json(
            """
            {"records":{"list":[
              {"msg_id":"m1","from_uk":"777","filelist":{"list":[
                {"fs_id":"1","server_filename":"a.mp3","path":"/a.mp3","isdir":"0"}]}},
              {"msg_id":"m2","from_uk":"777","xfilelist":{"list":[
                {"fs_id":"1","server_filename":"a.mp3","path":"/a.mp3","isdir":"0"},
                {"fs_id":"2","server_filename":"b.mp3","path":"/b.mp3","isdir":"0"}]}},
              {"msg_id":"m3","from_uk":"777","file_list":[
                {"fs_id":"3","server_filename":"c.mp3","path":"/c.mp3","isdir":"0"}]},
              {"msg_id":"","from_uk":"777","filelist":{"list":[
                {"fs_id":"9","server_filename":"x.mp3","path":"/x.mp3","isdir":"0"}]}},
              {"msg_id":"m5","from_uk":"  ","filelist":{"list":[
                {"fs_id":"8","server_filename":"y.mp3","path":"/y.mp3","isdir":"0"}]}}
            ]}}
            """.trimIndent(),
        )

        val entries = PanApi.parseRoots(payload, gid = "contact-777")

        // 同一份文件出现在多条消息里只保留第一次（m1 的那条），
        // 否则列表会出现两行同一个文件；msg_id / from_uk 为空的消息整体丢弃
        assertEquals(listOf("1", "2", "3"), entries.map { it.fileId })
        assertEquals(listOf("m1", "m2", "m3"), entries.map { it.messageId })
        assertTrue(entries.all { it.gid == "contact-777" })
    }

    @Test
    fun `联系人目录去重按发送者分组`() {
        // fs_id 只在自己网盘内唯一：不同发送者的同号文件是不同文件，不能被合并
        val payload = json(
            """
            {"records":{"list":[
              {"msg_id":"m1","from_uk":"777","filelist":{"list":[
                {"fs_id":"1","server_filename":"a.mp3","path":"/a.mp3","isdir":"0"}]}},
              {"msg_id":"m2","from_uk":"888","filelist":{"list":[
                {"fs_id":"1","server_filename":"b.mp3","path":"/b.mp3","isdir":"0"}]}}
            ]}}
            """.trimIndent(),
        )

        val entries = PanApi.parseRoots(payload, gid = "contact-777")

        assertEquals(listOf("a.mp3", "b.mp3"), entries.map { it.name })
        assertEquals(listOf("777", "888"), entries.map { it.owner })
    }

    @Test
    fun `群组子目录继承父条目的会话信息`() {
        val parent = entry(gid = "1001", messageId = "m1", owner = "u1", fileId = "dir-1")
        val payload = json(
            """{"records":[{"fs_id":"9","server_filename":"b.mp3","path":"/课程/b.mp3","isdir":"0"}]}""",
        )

        val child = PanApi.parseChildren(payload, parent).single()

        assertEquals("1001", child.gid)
        assertEquals("m1", child.messageId)
        assertEquals("u1", child.owner)
        assertEquals("9", child.fileId)
    }

    // ===================== 最近更新 =====================

    @Test
    fun `最近更新为空时返回空表而不是崩溃`() {
        // 部分群组在没有最近更新时会显式返回 null
        assertEquals(emptyList<PanEntry>(), PanApi.parseRecent(json("""{"data":{"recentlist":null}}""")))
    }

    @Test
    fun `最近更新按 fsid 读取条目 id`() {
        val payload = json(
            """
            {"data":{"recentlist":[{"gid":"1001","msg_id":"m1","fuk":"u1","fsid":"9",
              "server_filename":"a.mp3","path":"/a.mp3","size":"10","isdir":"0","md5":"m"}]}}
            """.trimIndent(),
        )

        val item = PanApi.parseRecent(payload).single()

        assertEquals("9", item.fileId)
        assertEquals("m1", item.messageId)
        assertEquals("u1", item.owner)
        assertEquals("m", item.md5)
    }

    // ===================== 分享链接 =====================

    @Test
    fun `分享链接兼容顶层与 data 内层`() {
        val top = PanApi.parseShare(json("""{"link":"https://pan.baidu.com/s/1x","pwd":"abcd"}"""))
        assertEquals("https://pan.baidu.com/s/1x", top?.link)
        assertEquals("abcd", top?.pwd)

        val nested = PanApi.parseShare(
            json("""{"data":{"shortlink":"https://pan.baidu.com/s/1y","extract_code":"efgh"}}"""),
        )
        assertEquals("https://pan.baidu.com/s/1y", nested?.link)
        assertEquals("efgh", nested?.pwd)
    }

    @Test
    fun `分享结果没有 http 链接时返回 null`() {
        assertNull(PanApi.parseShare(json("""{"errno":0}""")))
        // 只认 http(s) 开头，裸域名不算
        assertNull(PanApi.parseShare(json("""{"link":"pan.baidu.com/s/1x"}""")))
    }

    @Test
    fun `分享链接缺提取码时密码为空串`() {
        assertEquals("", PanApi.parseShare(json("""{"link":"https://pan.baidu.com/s/1x"}"""))?.pwd)
    }

    // ===================== 写操作错误码 =====================

    @Test
    fun `单项错误码优先于外层成功`() {
        assertEquals(0, PanApi.writeErrno(json("""{"errno":0}""")))
        assertEquals(-6, PanApi.writeErrno(json("""{"errno":-6}""")))
        assertEquals(
            -9,
            PanApi.writeErrno(json("""{"errno":0,"info":[{"errno":0},{"errno":-9}]}""")),
        )
        assertEquals(0, PanApi.writeErrno(json("""{"errno":0,"info":[]}""")))
    }

    @Test
    fun `错误码翻译成用户看得懂的话`() {
        assertTrue(PanApi.writeError("重命名", -6, json("{}")).contains("登录态已失效"))
        assertTrue(PanApi.writeError("重命名", -7, json("{}")).contains("同名文件"))
        assertTrue(PanApi.writeError("重命名", 3, json("{}")).contains("文件名不合法"))
        assertTrue(PanApi.writeError("复制", 4, json("{}")).contains("空间不足"))
        assertTrue(PanApi.writeError("删除", 132, json("{}")).contains("安全验证"))
        assertTrue(PanApi.writeError("删除", -20, json("{}")).contains("拒绝"))
        assertTrue(PanApi.writeError("删除", 123456, json("{}")).contains("网盘请求失败"))
    }

    @Test
    fun `错误提示带上操作名、错误码与网盘原文`() {
        val message = PanApi.writeError("删除", -9, json("""{"show_msg":"文件不存在"}"""))

        assertTrue(message.startsWith("删除："))
        assertTrue(message.contains("（错误码 -9）"))
        assertTrue(message.contains("网盘提示：文件不存在"))
    }

    @Test
    fun `errno 为 0 时也要给出可读提示`() {
        val message = PanApi.writeError("新建文件夹", 0, json("{}"))

        assertTrue(message.startsWith("「新建文件夹」失败"))
        assertTrue(message.contains("错误码 0"))
    }

    // ===================== 取址表单 =====================

    private fun formField(form: String, key: String): String =
        form.split("&").first { it.startsWith("$key=") }
            .removePrefix("$key=")
            .let { URLDecoder.decode(it, "UTF-8") }

    @Test
    fun `群组取址表单带 gid 与消息 id`() {
        val target = entry(gid = "1001", messageId = "m1", owner = "u1", fileId = "f1")

        val form = PanApi.mediaLinkBody(target, viewer = "viewer-uk")
        val extra = JSONObject(formField(form, "extra"))

        assertEquals("group", extra.getString("type"))
        assertEquals("1001", extra.getString("gid"))
        assertEquals("m1", formField(form, "primaryid"))
        assertEquals("viewer-uk", formField(form, "uk"))
        assertEquals(listOf("f1"), JSONArray(formField(form, "fid_list")).let { array ->
            List(array.length()) { array.getString(it) }
        })
    }

    @Test
    fun `联系人取址表单换成单人类型`() {
        val target = entry(gid = "contact-777", messageId = "m1", owner = "777", fileId = "f1")

        val form = PanApi.mediaLinkBody(target, viewer = "viewer-uk")
        val extra = JSONObject(formField(form, "extra"))

        assertEquals("single", extra.getString("type"))
        assertEquals("777", extra.getString("from_uk"))
        // 自己发给自己的消息，to_uk 用 viewer；否则用对方
        assertEquals("viewer-uk", extra.getString("to_uk"))
    }

    // 说明：mediaCaptchaForm / parseMediaCaptcha / parseMediaLink / parseDiskDownloadLink
    // 都以 android.net.Uri 为出入参，JVM 单测里无法构造（mockable android.jar 的方法一律抛异常）。
    // 这几条需要 Robolectric 或设备上的 instrumented test，当前未覆盖。

    // ===================== 路径 =====================

    @Test
    fun `明文路径原样返回`() {
        assertEquals("/课程/第1讲.mp3", PanApi.readablePath("/课程/第1讲.mp3"))
        assertEquals("", PanApi.readablePath(""))
        // 不以 %2F 开头的字符串即使含 % 也不动它
        assertEquals("/100%完成.mp3", PanApi.readablePath("/100%完成.mp3"))
    }
}
