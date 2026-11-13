package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UploadCommitTest {
    @Test
    fun cancelledTransferCannotStartCommit() {
        val commit = UploadCommit { _, _ -> fail("must not checkpoint a cancelled transfer") }
        assertTrue(commit.cancel())
        assertTrue(runCatching { commit.begin() }.isFailure)
    }

    @Test
    fun submittedTransferRejectsCancellationAndRecordsRemoteId() {
        val states = mutableListOf<Pair<UploadCommitState, String>>()
        val commit = UploadCommit { state, id -> states.add(state to id) }
        commit.begin()
        assertFalse(commit.cancel())
        commit.accepted("remote-file")
        assertFalse(commit.cancel())
        assertEquals(listOf(UploadCommitState.SUBMITTING to "", UploadCommitState.ACCEPTED to "remote-file"), states)
    }

    @Test
    fun acknowledgedRejectionRestoresCancellation() {
        val commit = UploadCommit { _, _ -> }
        commit.begin()
        commit.rejected()
        assertTrue(commit.cancel())
    }
}
