package com.hnwd.bdplayer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView

/**
 * 已下载视频的应用内播放页（目前只服务 HLS 转码副本 `.ts`）。
 *
 * 为什么不用 `ACTION_VIEW` 甩给外部播放器：`/mbox/msg/streaming` 下发的分片拼成的是
 * 一条长 MPEG-TS，字节层 PTS / PCR 全程连续、CC 也已由 [HlsTsNormalizer] 接成一条流
 * （ffmpeg 0 corrupt，见 `docs/百度群组视频下载失败-分析与修复.md` §5.4 / §5.5），
 * 但外部播放器要自己靠 PCR 重建时钟、处理周期性重下发的 PAT/PMT 与 ADTS 串联音频，
 * 部分实现会出现「画面先出、声音慢 2~3 秒」。应用内播放走 ExoPlayer 的
 * `ProgressiveMediaSource` + `TsExtractor`，与在线播放同一套解码与音频时钟同步机制。
 *
 * 产物本身不变：仍是 `.ts`，分享、「用其他应用打开」照常可用。本页不复用
 * `VideoPlaybackPage`（媒体页，独立演进），只做一件事：把本地文件播好。
 */
@OptIn(UnstableApi::class)
@Suppress("UnsafeOptInUsageError")
class LocalVideoActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val mime = intent.type ?: "video/mp2t"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            controllerShowTimeoutMs = 3_000
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        }
        root.addView(topAction("‹ 返回", atStart = true) { finish() })
        if (title.isNotBlank()) root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            background = ColorDrawable(Color.argb(120, 0, 0, 0))
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                leftMargin = dp(84)
                rightMargin = dp(150)
            }
        })
        root.addView(topAction("用其他应用打开", atStart = false) {
            val external = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(external, "用其他应用打开")) }
                .onFailure { Toast.makeText(this, "没有可用的播放器", Toast.LENGTH_SHORT).show() }
        })
        setContentView(root)

        // 拼接后的 TS 里每个分片都重复下发同一份 PMT：SINGLE_PMT 让 extractor
        // 只认第一份节目表，不因为后续 PAT/PMT 又重新初始化轨道。
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        val source = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(this), extractors,
        ).createMediaSource(MediaItem.fromUri(uri))
        val created = ExoPlayer.Builder(this).build().also { player = it }
        created.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@LocalVideoActivity,
                    "播放失败：${error.errorCodeName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        })
        view.player = created
        created.setMediaSource(source)
        created.prepare()
        created.playWhenReady = true
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun topAction(label: String, atStart: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        background = ColorDrawable(Color.argb(150, 0, 0, 0))
        val pad = dp(12)
        setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad)
        setOnClickListener { onClick() }
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or if (atStart) Gravity.START else Gravity.END
            val margin = dp(12)
            topMargin = margin
            leftMargin = margin
            rightMargin = margin
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, uri: Uri, title: String, mime: String): Intent =
            Intent(context, LocalVideoActivity::class.java)
                .setDataAndType(uri, mime)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
