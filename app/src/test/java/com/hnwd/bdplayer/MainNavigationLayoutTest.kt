package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationLayoutTest {
    @Test
    fun `窗口宽度跨过两级断点时切换导航形态`() {
        assertEquals(MainNavigationLayout.BOTTOM, MainNavigationLayout.forWidth(599))
        assertEquals(MainNavigationLayout.RAIL, MainNavigationLayout.forWidth(600))
        assertEquals(MainNavigationLayout.RAIL, MainNavigationLayout.forWidth(840))
        assertEquals(MainNavigationLayout.RAIL, MainNavigationLayout.forWidth(959))
        assertEquals(MainNavigationLayout.SIDEBAR, MainNavigationLayout.forWidth(960))
    }
}
