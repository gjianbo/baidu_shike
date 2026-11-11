package com.hnwd.bdplayer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DriveSourceRouter] 是进程单例，绑定的是捕获 Activity 的闭包；用例结束必须清理。 */
class DriveSourceRouterTest {

    private val owner = Any()

    @After
    fun tearDown() {
        DriveSourceRouter.unbind(owner)
    }

    @Test
    fun `解绑后回到安全的默认值`() {
        DriveSourceRouter.bindActiveSource(owner) { "gid-1" }
        DriveSourceRouter.bindDiskProvider(owner, { SourceProvider.QUARK }, {})
        DriveSourceRouter.unbind(owner)
        assertEquals("", DriveSourceRouter.activeSourceId())
        assertEquals(SourceProvider.BAIDU, DriveSourceRouter.diskProvider())
    }

    @Test
    fun `绑定后能读到注入值并写回`() {
        DriveSourceRouter.bindActiveSource(owner) { "quark:abc" }
        var written: SourceProvider? = null
        DriveSourceRouter.bindDiskProvider(owner, { SourceProvider.QUARK }, { written = it })
        assertEquals("quark:abc", DriveSourceRouter.activeSourceId())
        assertEquals(SourceProvider.QUARK, DriveSourceRouter.diskProvider())
        DriveSourceRouter.setDiskProvider(SourceProvider.BAIDU)
        assertEquals(SourceProvider.BAIDU, written)
    }

    @Test
    fun `unbind 清掉自己的闭包后不再写回已销毁的 Activity`() {
        DriveSourceRouter.bindActiveSource(owner) { "gid-1" }
        var writes = 0
        DriveSourceRouter.bindDiskProvider(owner, { SourceProvider.QUARK }, { writes++ })
        DriveSourceRouter.setDiskProvider(SourceProvider.BAIDU)
        assertEquals(1, writes)

        DriveSourceRouter.unbind(owner)
        assertEquals("", DriveSourceRouter.activeSourceId())
        assertEquals(SourceProvider.BAIDU, DriveSourceRouter.diskProvider())
        DriveSourceRouter.setDiskProvider(SourceProvider.BAIDU)
        assertEquals(1, writes)
    }

    @Test
    fun `别的 owner 调 unbind 不能清掉当前绑定`() {
        DriveSourceRouter.bindActiveSource(owner) { "gid-1" }
        DriveSourceRouter.bindDiskProvider(owner, { SourceProvider.QUARK }, {})
        DriveSourceRouter.unbind(Any())
        assertEquals("gid-1", DriveSourceRouter.activeSourceId())
        assertEquals(SourceProvider.QUARK, DriveSourceRouter.diskProvider())
    }

    @Test
    fun `新实例重新绑定后旧实例 unbind 不生效`() {
        val oldOwner = Any()
        DriveSourceRouter.bindActiveSource(oldOwner) { "old" }
        DriveSourceRouter.bindActiveSource(owner) { "new" }
        DriveSourceRouter.unbind(oldOwner)
        assertEquals("new", DriveSourceRouter.activeSourceId())
    }
}
