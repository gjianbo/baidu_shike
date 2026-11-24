package com.hnwd.bdplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkLoginLogicTest {
    @Test
    fun acceptsHttpsWithImplicitOrExplicitDefaultPort() {
        assertTrue(isQuarkH5LoginOrigin("https", "uop.quark.cn"))
        assertTrue(isQuarkH5LoginOrigin("https", "uop.quark.cn:443"))
    }

    @Test
    fun acceptsAsciiCaseVariantsOfSchemeAndAuthority() {
        assertTrue(isQuarkH5LoginOrigin("HTTPS", "UOP.QUARK.CN"))
        assertTrue(isQuarkH5LoginOrigin("Https", "uop.QUARK.cn:443"))
    }

    @Test
    fun rejectsOtherSchemesAndMissingComponents() {
        assertFalse(isQuarkH5LoginOrigin("http", "uop.quark.cn"))
        assertFalse(isQuarkH5LoginOrigin(null, "uop.quark.cn"))
        assertFalse(isQuarkH5LoginOrigin("https", null))
        assertFalse(isQuarkH5LoginOrigin("https", ""))
    }

    @Test
    fun rejectsOtherHostsAndPorts() {
        for (authority in listOf("quark.cn", "other.quark.cn", "uop.quark.cn.example", "uop.quark.cn:444")) {
            assertFalse(authority, isQuarkH5LoginOrigin("https", authority))
        }
    }

    @Test
    fun rejectsUserInfoAndNoncanonicalAuthorities() {
        for (authority in listOf("user@uop.quark.cn", "uop.quark.cn@other.example", "uop.quark.cn:", "%75op.quark.cn")) {
            assertFalse(authority, isQuarkH5LoginOrigin("https", authority))
        }
    }

    @Test
    fun rejectsUnicodeCaseFoldingToAscii() {
        assertFalse(isQuarkH5LoginOrigin("httpſ", "uop.quark.cn"))
        assertFalse(isQuarkH5LoginOrigin("https", "uop.quarK.cn"))
    }
}
