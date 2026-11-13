package com.hnwd.bdplayer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
            intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) >= 0
        ) {
            DownloadQueueJob.enqueue(context)
        }
    }
}
