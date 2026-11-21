package com.hnwd.bdplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTransferLogic2Test {

    private fun entry(
        name: String, size: String, directory: Boolean = false, gid: String = "123",
        msgId: String = "999", owner: String = "888", fileId: String = "777",
        path: String = "/$name",
    ) = PanEntry(
        gid = gid, messageId = msgId, owner = owner, fileId = fileId,
        name = name, path = path, size = size, directory = directory,
    )

    private val GB = 1024L * 1024 * 1024
    private val MB = 1024L * 1024

    @Test
    fun quota_parsesRealServerShape() {
        // 实测 /api/quota 原样字段
        val json = JSONObject(
            """{"errno":0,"free":3411277774848,"total":12207370797056,"used":9759464397279}""",
        )
        val quota = GroupTransferLogic2.parseQuota(json)
        assertNotNull(quota)
        assertEquals(3411277774848L, quota!!.freeBytes)
        assertEquals(12207370797056L, quota.totalBytes)
    }

    @Test
    fun quota_usesUnknownNotZeroWhenFieldsMissing() {
        // 缺字段必须判「未知」，否则会把「查不到」当成「没空间」而错杀转存。
        assertNull(GroupTransferLogic2.parseQuota(JSONObject().put("errno", 0)))
        assertNull(GroupTransferLogic2.parseQuota(JSONObject().put("errno", -6).put("free", 1).put("total", 2)))
        // free 可以是 0（真的满了），但要能区分出来
        assertNotNull(GroupTransferLogic2.parseQuota(JSONObject().put("errno", 0).put("free", 0).put("total", 100)))
    }

    @Test
    fun roomCheck_reservesHeadroomAboveBareSize() {
        val need = 1 * GB
        // 刚好等于所需大小：不留余量，应判不够
        assertFalse(GroupTransferLogic2.hasRoomFor(need, need))
        // 多出 5%（=51.2MB）仍不足 64MB 保底，判不够
        assertFalse(GroupTransferLogic2.hasRoomFor(need + 60 * MB, need))
        // 多出 64MB 才够
        assertTrue(GroupTransferLogic2.hasRoomFor(need + 64 * MB, need))
        // 空盘 / 负值一律不放行
        assertFalse(GroupTransferLogic2.hasRoomFor(0, need))
        assertFalse(GroupTransferLogic2.hasRoomFor(-1, need))
    }

    @Test
    fun roomCheck_usesFivePercentForLargeFiles() {
        val need = 100 * GB
        assertTrue(GroupTransferLogic2.reserveFor(need) > 64 * MB)
        assertEquals(need / 20, GroupTransferLogic2.reserveFor(need))
        assertEquals(64 * MB, GroupTransferLogic2.reserveFor(1 * MB))
    }

    @Test
    fun canPrefetch_checksEligibilityAndRoomNotSize() {
        val plenty = 1000 * GB
        // 小文件 + 群组来源 + 空间够 → 走转存
        assertTrue(GroupTransferLogic2.canPrefetch(entry("a.mp4", "${10 * MB}"), plenty))
        // 大文件也允许尝试：大小不是可靠判据，可用性由 dlink 形状判定
        assertTrue(GroupTransferLogic2.canPrefetch(entry("b.mp4", "${200 * MB}"), plenty))
        // 空间不够
        assertFalse(GroupTransferLogic2.canPrefetch(entry("c.mp4", "${10 * MB}"), 1 * MB))
        // 目录不转存
        assertFalse(GroupTransferLogic2.canPrefetch(entry("d", "0", directory = true), plenty))
        // 我的网盘来的条目不是群组条目（gid 带 dir- 前缀）
        assertFalse(GroupTransferLogic2.canPrefetch(entry("e.mp4", "${1 * MB}", gid = "dir-1"), plenty))
        // size 缺失 → 不赌
        assertFalse(GroupTransferLogic2.canPrefetch(entry("f.mp4", ""), plenty))
    }

    @Test
    fun inspectDlink_flagsTheTwoRealSamples() {
        // 实测样本 A：11.9KB JSON，skew=0、expires=8h → 能下
        val ok = GroupTransferLogic2.inspectDlink(
            "https://d.pcs.baidu.com/file/643242bb8j971af3f05be0b993b4acab?fid=654702226-250528-617574339034415&rt=pr&expires=8h&dstime=1795301711",
            1795301711L,
        )
        assertNotNull(ok)
        assertTrue(ok!!.usable)
        assertEquals("8h", ok.expires)

        // 实测样本 B：160.7MB mp4，skew=7200、expires=1h → 一定 31360
        val bad = GroupTransferLogic2.inspectDlink(
            "https://d.pcs.baidu.com/file/498e3ed66u59e2aaaa32ad28a72abe85?fid=654702226-250528-277269499701605&rt=pr&expires=1h&dstime=1795294764",
            1795301964L,
        )
        assertNotNull(bad)
        assertFalse(bad!!.usable)
        assertEquals(7200L, bad.skewSeconds)
    }

    @Test
    fun inspectDlink_rejectsUnknownShapesInsteadOfGuessing() {
        assertNull(GroupTransferLogic2.inspectDlink("https://x/y", 1L))
        assertNull(GroupTransferLogic2.inspectDlink("https://x/y?expires=8h", 1L))
        assertNull(GroupTransferLogic2.inspectDlink("https://x/y?dstime=1", 1L))
    }

    @Test
    fun inspectDlink_toleratesSmallClockDrift() {
        // 一分钟内的偏差是正常时钟漂移，不能因为 5 秒误差就判死
        val drift = GroupTransferLogic2.inspectDlink(
            "https://x/y?expires=8h&dstime=1000", 1030L,
        )
        assertTrue(drift!!.usable)
    }

    @Test
    fun matchTransferred_handlesOndupNewcopyRename() {
        // ondup=newcopy 会把同名文件改成 xxx(1).mp4
        val list = listOf(
            entry("5、表达的选择(1).mp4", "${100 * MB}", fileId = "1"),
            entry("别的文件.mp4", "${100 * MB}", fileId = "2"),
        )
        val hit = GroupTransferLogic2.matchTransferred(list, "5、表达的选择.mp4", 100 * MB)
        assertNotNull(hit)
        assertEquals("1", hit!!.fileId)
    }

    @Test
    fun matchTransferred_picksClosestSizeWhenNameRepeats() {
        val list = listOf(
            entry("same.mp4", "${10 * MB}", fileId = "small"),
            entry("same.mp4", "${500 * MB}", fileId = "big"),
        )
        assertEquals("big", GroupTransferLogic2.matchTransferred(list, "same.mp4", 500 * MB)!!.fileId)
        assertEquals("small", GroupTransferLogic2.matchTransferred(list, "same.mp4", 10 * MB)!!.fileId)
    }

    @Test
    fun matchTransferred_returnsNullWhileStillLanding() {
        // 异步落盘未完成时目录里没有它，必须判 null 让调用方继续轮询，而不是误配到别的文件
        assertNull(GroupTransferLogic2.matchTransferred(emptyList(), "a.mp4", 1 * MB))
        assertNull(
            GroupTransferLogic2.matchTransferred(
                listOf(entry("b.mp4", "${1 * MB}")), "a.mp4", 1 * MB,
            ),
        )
        // 扩展名不同（a.mp4 vs a.mkv）不算同一文件
        assertNull(
            GroupTransferLogic2.matchTransferred(
                listOf(entry("a.mkv", "${1 * MB}")), "a.mp4", 1 * MB,
            ),
        )
    }

    @Test
    fun matchTransferred_ignoresDirectories() {
        val list = listOf(entry("a.mp4", "0", directory = true, fileId = "dir"))
        assertNull(GroupTransferLogic2.matchTransferred(list, "a.mp4", 1 * MB))
    }

    @Test
    fun stagingSnapshot_onlyTreatsNewPathsAsOwnCopies() {
        val dir = GroupTransferLogic2.STAGING_DIR
        fun staged(name: String, id: String) =
            entry(name, "${100 * MB}", fileId = id, path = "$dir/$name")
        val existing = staged("5、表达的选择(1).mp4", "old")
        val fresh = staged("5、表达的选择(2).mp4", "new")
        val baseline = setOf(existing.path)

        assertTrue(GroupTransferLogic2.isStagingPath(existing))
        assertFalse(GroupTransferLogic2.isStagingPath(entry("根目录.mp4", "1")))
        assertFalse(GroupTransferLogic2.isStagingPath(staged("子目录", "dir").copy(directory = true)))
        assertTrue(GroupTransferLogic2.isOwnStagingCopy(fresh, baseline))
        assertFalse(GroupTransferLogic2.isOwnStagingCopy(existing, baseline))

        // 没有快照过滤时 matchTransferred 会误配到用户原有的同名文件（old）；
        // 加了基线之后只认本次新增的 new，超时清理才不会误删用户文件。
        assertEquals(
            "new",
            GroupTransferLogic2.matchNewlyTransferred(
                baseline, listOf(existing, fresh), "5、表达的选择.mp4", 100 * MB,
            )!!.fileId,
        )
        assertNull(
            GroupTransferLogic2.matchNewlyTransferred(
                baseline, listOf(existing), "5、表达的选择.mp4", 100 * MB,
            ),
        )
    }

    @Test
    fun ownCopyNamed_limitsCleanupToThisTransfer() {
        val dir = GroupTransferLogic2.STAGING_DIR
        fun staged(name: String) = entry(name, "${1 * MB}", path = "$dir/$name")
        val baseline = setOf("$dir/旧文件.mp4")
        assertTrue(
            GroupTransferLogic2.isOwnCopyNamed(staged("课程(1).mp4"), baseline, "课程.mp4"),
        )
        // 快照里已有 / 不在专用目录 / 扩展名不同 / 名字不相干，一律不碰
        assertFalse(
            GroupTransferLogic2.isOwnCopyNamed(staged("old.mp4"), setOf("$dir/old.mp4"), "课程.mp4"),
        )
        assertFalse(
            GroupTransferLogic2.isOwnCopyNamed(entry("课程(1).mp4", "1"), baseline, "课程.mp4"),
        )
        assertFalse(
            GroupTransferLogic2.isOwnCopyNamed(staged("课程(1).mkv"), baseline, "课程.mp4"),
        )
        assertFalse(
            GroupTransferLogic2.isOwnCopyNamed(staged("别的文件.mp4"), baseline, "课程.mp4"),
        )
    }

    @Test
    fun notices_distinguishReadyAndPending() {
        assertTrue(GroupTransferLogic2.prefetchNotice(true).contains("原始文件"))
        assertTrue(GroupTransferLogic2.prefetchNotice(false).contains("落盘"))
    }

    @Test
    fun stagingDirIsDedicatedAndAbsolute() {
        assertTrue(GroupTransferLogic2.STAGING_DIR.startsWith("/"))
        // 专用目录，不能是根目录，避免污染用户网盘
        assertTrue(GroupTransferLogic2.STAGING_DIR.length > 1)
    }
}
