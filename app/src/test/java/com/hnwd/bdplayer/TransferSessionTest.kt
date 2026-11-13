package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSessionTest {
    @Test
    fun quarkBindingIgnoresCookieOrderAndBrowserMetadata() {
        val id = TransferSession.quarkSessionId("__pus=session-a; __puus=cdn-a; unrelated=before")
        assertEquals(id, TransferSession.quarkSessionId("unrelated=after; __puus=cdn-a; __pus=session-a"))
        assertEquals(64, id?.length)
        assertNotEquals(id, TransferSession.quarkSessionId("__pus=session-b; __puus=cdn-a"))
        assertNotEquals(id, TransferSession.quarkSessionId("__pus=session-a; __puus=cdn-b"))
        assertNotEquals(id, TransferSession.quarkSessionId("__pus=session-a"))
        assertNull(TransferSession.quarkSessionId("__pus=; unrelated=after"))
    }

    @Test
    fun accountCheckRejectsBlankAndDifferentIdentities() {
        for (provider in SourceProvider.entries) {
            TransferSession.requireAccount(provider, "account-a", "account-a")
            assertTrue(runCatching {
                TransferSession.requireAccount(provider, "account-a", "account-b")
            }.exceptionOrNull() is TransferSession.Failure)
            assertTrue(runCatching {
                TransferSession.requireAccount(provider, "", "")
            }.exceptionOrNull() is TransferSession.Failure)
        }
    }
}
