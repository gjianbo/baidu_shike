package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CollectorSiteCookiesTest {
    @Test
    fun keepsOnlyUsableCookiesForTheSelectedPlatform() {
        val session = "#HttpOnly_.weibo.com\tTRUE\t/\tTRUE\t0\tsession\tfixture"
        val expired = ".weibo.com\tTRUE\t/\tTRUE\t100\told\tfixture"
        val input = "# Netscape HTTP Cookie File\r\n$session\r\n$expired"
        assertEquals("# Netscape HTTP Cookie File\n$session\n",
            CollectorSiteCookies.normalize(CollectorSite.WEIBO, input, now = 101))
        assertNull(CollectorSiteCookies.normalize(CollectorSite.WEIBO, expired, now = 101))
    }

    @Test
    fun rejectsCookiesForOtherDomains() {
        val wrongSite = ".tiktok.com.example.org\tTRUE\t/\tTRUE\t0\tsession\tfixture"
        assertThrows(IllegalArgumentException::class.java) {
            CollectorSiteCookies.normalize(CollectorSite.TIKTOK, wrongSite)
        }
    }

    @Test
    fun recognizesPublicPostAndShortVideoLinks() {
        val post = requireNotNull(VideoCollectorLogic.parse("看视频 https://weibo.com/7827771738/N4xlMvjhI?share=1"))
        assertEquals(VideoCollectorLogic.Platform.WEIBO, post.platform)
        assertEquals("https://weibo.com/7827771738/N4xlMvjhI", post.canonicalUrl)
        val short = requireNotNull(VideoCollectorLogic.parse("https://vm.tiktok.com/ZMExample/?utm_source=share"))
        assertEquals(VideoCollectorLogic.Platform.TIKTOK, short.platform)
        assertEquals("https://vm.tiktok.com/ZMExample/", short.canonicalUrl)
        assertNull(VideoCollectorLogic.parse("https://www.tiktok.com/@someone"))
        assertNull(VideoCollectorLogic.parse("https://weibo.com.example.org/7827771738/N4xlMvjhI"))
    }
    @Test
    fun newSitesKeepCookieDomainsAndMinimumVersionsSeparate() {
        val youtube = ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\ttest"
        val xiaohongshu = ".xiaohongshu.com\tTRUE\t/\tTRUE\t0\ta1\ttest"
        org.junit.Assert.assertNotNull(CollectorSiteCookies.normalize(CollectorSite.YOUTUBE, youtube))
        org.junit.Assert.assertNotNull(CollectorSiteCookies.normalize(CollectorSite.XIAOHONGSHU, xiaohongshu))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CollectorSiteCookies.normalize(CollectorSite.YOUTUBE, xiaohongshu)
        }
        org.junit.Assert.assertEquals(3L, CollectorSite.YOUTUBE.minExtensionVersion)
        org.junit.Assert.assertEquals(3L, CollectorSite.XIAOHONGSHU.minExtensionVersion)
    }

}
