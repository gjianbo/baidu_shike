package com.hnwd.bdplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedHomeAudioTest {
    @Test
    fun `缓存首页播放仅续接同一个账号与来源`() {
        val request = CachedHomeAudio(account = "123", sourceId = "456")
        val token = AudioPlayRequestGate.begin()

        assertTrue(request.isCurrent("123", "456", token))
        assertFalse(request.isCurrent("", "456", token))
        assertFalse(request.isCurrent("789", "456", token))
        assertFalse(request.isCurrent("123", "999", token))
    }

    @Test
    fun `媒体控制取消和新播放请求都会使旧点击失效`() {
        val request = CachedHomeAudio(account = "123", sourceId = "456")
        val cancelledToken = AudioPlayRequestGate.begin()
        AudioPlayRequestGate.cancelCurrent()
        assertFalse(request.isCurrent("123", "456", cancelledToken))

        val replacedToken = AudioPlayRequestGate.begin()
        val currentToken = AudioPlayRequestGate.begin()
        assertFalse(request.isCurrent("123", "456", replacedToken))
        assertTrue(request.isCurrent("123", "456", currentToken))
    }
}
