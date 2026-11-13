package com.hnwd.bdplayer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Keeps the process alive while resolving links and publishing finished files. */
class DownloadQueueJob : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private var running: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = params
        return next(params)
    }

    private fun next(params: JobParameters): Boolean {
        if (running !== params) return false
        val work = params.dequeueWork()
        if (work == null) {
            // Dequeuing past the end finishes an enqueued job in JobScheduler.
            running = null
            return false
        }
        AppIo.execute {
            try {
                DownloadCenter.drain(applicationContext) {
                    main.post {
                        if (running === params) {
                            params.completeWork(work)
                            next(params)
                        }
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("ShikeDownload", "download queue interrupted", error)
                main.post {
                    if (running === params) {
                        running = null
                        jobFinished(params, true)
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (running === params) running = null
        return true
    }

    companion object {
        internal const val ID = 2103

        @Suppress("DEPRECATION") // JobWorkItem(Intent) also supports Android 10–13.
        internal fun enqueue(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(ID, ComponentName(context, DownloadQueueJob::class.java)).build()
            return runCatching {
                scheduler.enqueue(job, JobWorkItem(Intent())) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
        }
    }
}
