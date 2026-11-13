package com.hnwd.bdplayer

import java.io.IOException

/** Serializes user cancellation with the durable checkpoint before a remote commit. */
internal class UploadCommit(private val save: (UploadCommitState, String) -> Unit) {
    private var cancelled = false
    private var state = UploadCommitState.TRANSFERRING

    @Synchronized
    fun cancel(): Boolean {
        if (state != UploadCommitState.TRANSFERRING || cancelled) return false
        cancelled = true
        return true
    }

    @Synchronized
    fun begin() {
        if (cancelled) throw IOException("已取消上传")
        save(UploadCommitState.SUBMITTING, "")
        state = UploadCommitState.SUBMITTING
    }

    @Synchronized
    fun rejected() {
        save(UploadCommitState.TRANSFERRING, "")
        state = UploadCommitState.TRANSFERRING
    }

    @Synchronized
    fun accepted(remoteId: String) {
        save(UploadCommitState.ACCEPTED, remoteId)
        state = UploadCommitState.ACCEPTED
    }
}

internal enum class UploadCommitState { TRANSFERRING, SUBMITTING, ACCEPTED }
