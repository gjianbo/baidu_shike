package com.hnwd.bdplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.MotionEvent
import android.window.OnBackInvokedDispatcher
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs

@OptIn(UnstableApi::class)
internal class VideoPlaybackPage(
    private val activity: Activity,
    private val entry: PanEntry,
    source: MediaSource,
    private val account: String,
    private val history: AudioHistory,
    startPositionMs: Long?,
    playWhenReady: Boolean,
    retryOnExpiredLink: Boolean,
    startAudioOnly: Boolean,
    startFullscreen: Boolean,
    val orientationBeforeFullscreen: Int,
    onRetry: (Snapshot, Boolean) -> Unit,
    courses: List<PanEntry>,
    currentCourse: Int,
    courseSortLabel: String?,
    onSortCourses: (View, (List<PanEntry>, String) -> Unit) -> Unit,
    canLoadMoreCourses: () -> Boolean,
    onLoadMoreCourses: ((List<PanEntry>?) -> Unit) -> Unit,
    onSelectCourse: (PanEntry, Snapshot) -> Unit,
    onSwitch: (Int, Snapshot) -> Unit,
    onEnded: (Snapshot) -> Unit,
    isFavorite: Boolean,
    onFavorite: () -> Boolean,
    private val onClearBackgroundAudio: () -> Unit,
    onClose: (VideoPlaybackPage) -> Unit,
) {
    private val dialog = object : Dialog(activity, android.R.style.Theme_Material_NoActionBar) {
        @Deprecated("Used for Android versions before predictive back")
        override fun onBackPressed() = handleBack()
    }
    private val player: ExoPlayer = ExoPlayer.Builder(activity)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
        .apply { setPlaybackParameters(PlaybackParameters(PlaybackSpeed.video(activity))) }
    private val video = (activity.layoutInflater.inflate(R.layout.view_video_player, null) as PlayerView).apply {
        this.player = this@VideoPlaybackPage.player
        // 控制层默认不常驻：单击画面显示、3 秒无操作自动收起（竖屏与横屏一致）
        controllerShowTimeoutMs = 3_000
        controllerHideOnTouch = true
    }
    private val progress = activity.getSharedPreferences("video_progress", Activity.MODE_PRIVATE)
    private val progressKey = AccountScope.key(
        "${entry.origin}:${entry.sourceId}:${entry.gid}:${entry.messageId}:${entry.fileId}",
    )
    private val legacyProgressKey = AccountScope.key("${entry.gid}:${entry.messageId}:${entry.fileId}")
    private var readyToSave = false
    private var keepOrientationOnClose = false
    private var immersive = false
    private var listening = startAudioOnly
    private var backgroundSession = false
    private var audioControllerFuture: ListenableFuture<MediaController>? = null
    private lateinit var updateFullscreen: (Boolean) -> Unit

    private val actionLabelColor = Color.parseColor("#8E909C")
    private val actionActiveColor = Color.parseColor("#BFC6FF")
    private val starColor = Color.rgb(255, 200, 87)

    /** D 槽的一格：图标 24 + 标签 10sp，整格可点 */
    private class ActionCell(val root: LinearLayout, val icon: ImageView, val label: TextView)

    private fun handleBack() {
        if (immersive) updateFullscreen(false) else close()
    }

    private fun ensureAudioSession() {
        if (!listening || backgroundSession) return
        if (player.mediaItemCount == 0 || !player.playWhenReady) return
        backgroundSession = true
        onClearBackgroundAudio()
        VideoAudioSessionService.prepare(player)
        val token = SessionToken(activity, ComponentName(activity, VideoAudioSessionService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        audioControllerFuture = future
        future.addListener({
            if (!runCatching { future.get() }.isSuccess) {
                if (audioControllerFuture === future) {
                    audioControllerFuture = null
                    backgroundSession = false
                    VideoAudioSessionService.stop(activity)
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun stopAudioSession() {
        if (!backgroundSession) return
        backgroundSession = false
        audioControllerFuture?.let(MediaController::releaseFuture)
        audioControllerFuture = null
        VideoAudioSessionService.stop(activity)
    }

    fun pauseForBackground() {
        if (listening && backgroundSession) return
        pause()
    }

    init {
        val message = TextView(activity).apply {
            text = "正在缓冲…"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x99000000.toInt())
        }
        fun retryPlayback(automatic: Boolean) = onRetry(snapshot(), automatic)
        val retry = button("重试") { retryPlayback(false) }.apply { visibility = View.GONE }
        val title = TextView(activity).apply {
            text = entry.name
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        // 转音频后的画面区：大圆形耳机 + 两行文案，替代原来的小图标提示
        val audioStage = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = if (listening) View.VISIBLE else View.GONE
            addView(
                FrameLayout(activity).apply {
                    setBackgroundResource(R.drawable.bg_audio_stage)
                    addView(
                        ImageView(activity).apply {
                            setImageResource(R.drawable.ic_headset)
                            setColorFilter(Color.WHITE)
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setPadding(dp(26), dp(26), dp(26), dp(26))
                        },
                        FrameLayout.LayoutParams(-1, -1),
                    )
                },
                LinearLayout.LayoutParams(dp(72), dp(72)),
            )
            addView(
                TextView(activity).apply {
                    text = "正在以音频模式播放"
                    textSize = 13f
                    setTextColor(0xFFC5C5D0.toInt())
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) },
            )
        }
        fun actionCell(iconRes: Int, caption: String, description: String, action: () -> Unit): ActionCell {
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            val label = TextView(activity).apply {
                text = caption
                textSize = 10f
                setTextColor(actionLabelColor)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_video_icon_ripple)
                isClickable = true
                contentDescription = description
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
                setOnClickListener { action() }
            }
            return ActionCell(root, icon, label)
        }
        // 转音频是高频动作：竖屏顶栏常驻耳机（··· 左侧），不再藏在 D 槽或菜单里
        val audioOnly = iconButton(R.drawable.ic_headset, "转音频播放", padding = 10) {}
        fun paintListening() {
            audioOnly.contentDescription =
                if (listening) "退出音频模式，恢复画面" else "转音频播放"
            audioOnly.setBackgroundResource(
                if (listening) R.drawable.bg_play_accent else R.drawable.bg_video_icon_ripple
            )
            audioOnly.setColorFilter(
                if (listening) ContextCompat.getColor(activity, R.color.onAccent) else Color.WHITE
            )
        }
        fun setListening(enabled: Boolean) {
            listening = enabled
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, listening).build()
            audioStage.visibility = if (listening) View.VISIBLE else View.GONE
            paintListening()
            message.text = if (listening) "" else "正在恢复画面…"
            message.visibility = if (listening) View.GONE else View.VISIBLE
            if (enabled) ensureAudioSession() else stopAudioSession()
        }
        audioOnly.setOnClickListener { setListening(!listening) }
        audioOnly.isEnabled = false
        audioOnly.alpha = 0.35f
        paintListening()
        if (listening) setListening(true)
        val favorite = actionCell(R.drawable.ic_star, "收藏", "收藏") {}
        fun paintFavorite(starred: Boolean) {
            favorite.icon.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
            favorite.icon.setColorFilter(if (starred) starColor else Color.WHITE)
            favorite.label.setTextColor(if (starred) starColor else actionLabelColor)
            favorite.label.text = if (starred) "已收藏" else "收藏"
            favorite.root.contentDescription = if (starred) "取消收藏" else "收藏"
        }
        favorite.root.setOnClickListener { paintFavorite(onFavorite()) }
        paintFavorite(isFavorite)
        val previous = iconButton(R.drawable.ic_skip_prev, "上一节", padding = 14) {
            onSwitch(-1, snapshot())
        }
        val next = iconButton(R.drawable.ic_skip_next, "下一节", padding = 14) {
            onSwitch(1, snapshot())
        }
        fun shouldRotateForFullscreen() = !activity.isInMultiWindowMode && !activity.hasWideWindow &&
            activity.resources.configuration.smallestScreenWidthDp < WIDE_WINDOW_DP
        val fullscreen = iconButton(R.drawable.ic_fullscreen, "全屏") { updateFullscreen(!immersive) }
        // 倍速：控制行右端的文字胶囊，直接显示当前值，不占独立一格
        val speed = TextView(activity).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_video_chip)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            gravity = Gravity.CENTER
            minWidth = dp(46)
            isClickable = true
        }
        fun paintSpeed() {
            val value = player.playbackParameters.speed
            speed.text = PlaybackSpeed.label(value)
            speed.contentDescription = "播放倍速，当前 ${PlaybackSpeed.spoken(value)}"
        }
        paintSpeed()
        speed.setOnClickListener {
            val menu = PopupMenu(dialog.context, speed, Gravity.END)
            PlaybackSpeed.values.forEachIndexed { index, value ->
                menu.menu.add(0, index, index, PlaybackSpeed.spoken(value))
                    .setChecked(value == player.playbackParameters.speed)
            }
            menu.menu.setGroupCheckable(0, true, true)
            menu.setOnMenuItemClickListener { item ->
                val value = PlaybackSpeed.values[item.itemId]
                player.setPlaybackParameters(PlaybackParameters(value))
                PlaybackSpeed.setVideo(activity, value)
                paintSpeed()
                true
            }
            video.controllerShowTimeoutMs = 0
            menu.setOnDismissListener { video.controllerShowTimeoutMs = 3_000 }
            menu.show()
        }
        val playPause = iconButton(R.drawable.ic_play, "播放", padding = 16) {
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
            else {
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                player.play()
            }
        }.apply { setBackgroundResource(R.drawable.bg_play_accent) }
        fun paintPlayPause() {
            val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            playPause.contentDescription = if (playing) "暂停" else "播放"
            playPause.setColorFilter(ContextCompat.getColor(activity, R.color.onAccent))
        }
        paintPlayPause()
        // 传输组固定三件：上一 / 播放 / 下一。竖屏浮在画面底部居中，横屏并入进度行左端。
        // ±10 秒改用长按上一/下一，避免传输组再扩成五项破坏对称。
        previous.setOnLongClickListener {
            player.seekBack()
            true
        }
        next.setOnLongClickListener {
            player.seekForward()
            true
        }
        val transport = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(previous)
            addView(playPause)
            addView(next)
        }
        fun styleTransport(compact: Boolean) {
            val side = if (compact) dp(32) else dp(44)
            val sidePad = if (compact) dp(7) else dp(11)
            listOf(previous, next).forEach {
                it.layoutParams = LinearLayout.LayoutParams(side, side)
                it.setPadding(sidePad, sidePad, sidePad, sidePad)
            }
            val inset = if (compact) dp(9) else dp(16)
            val play = if (compact) dp(36) else dp(56)
            playPause.layoutParams = LinearLayout.LayoutParams(play, play).apply {
                marginStart = if (compact) dp(8) else dp(26)
                marginEnd = marginStart
            }
            playPause.setPadding(inset, inset, inset, inset)
        }
        var courseDialog: AlertDialog? = null
        var listedCourses = courses
        var selectedCourse = currentCourse
        var loadingCourses = false
        val courseHeading = TextView(activity).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        val moreCourses = button("加载更多") {}
        val sortCourses = button(courseSortLabel.orEmpty()) {}.apply {
            visibility = if (courseSortLabel == null) View.GONE else View.VISIBLE
            contentDescription = courseSortLabel?.let { "课程排序，当前 ${it.substringBefore(' ')}" }
        }
        val courseRows = ListView(activity).apply {
            divider = null
            isNestedScrollingEnabled = true
            setPadding(dp(8), 0, dp(8), dp(8))
            clipToPadding = false
        }
        val coursePanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF15151D.toInt())
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                addView(courseHeading, LinearLayout.LayoutParams(0, -1, 1f))
                addView(moreCourses, LinearLayout.LayoutParams(-2, dp(40)))
                addView(sortCourses, LinearLayout.LayoutParams(-2, dp(40)))
            }, LinearLayout.LayoutParams(-1, dp(52)))
            addView(courseRows, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val embeddedCourses = FrameLayout(activity).apply {
            addView(coursePanel, FrameLayout.LayoutParams(-1, -1))
        }
        val courseAdapter = object : BaseAdapter() {
            override fun getCount() = listedCourses.size
            override fun getItem(position: Int) = listedCourses[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val current = position == selectedCourse
                return ((convertView as? TextView) ?: TextView(activity).apply {
                    minHeight = dp(52)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    textSize = 14f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }).apply {
                    text = "${position + 1}.  ${getItem(position).name}"
                    setTextColor(if (current) 0xFFD4D8FF.toInt() else 0xFFE5E5EF.toInt())
                    setBackgroundColor(if (current) 0xFF292B46.toInt() else Color.TRANSPARENT)
                    contentDescription = if (current) "当前课程，$text" else text
                }
            }
        }
        courseRows.adapter = courseAdapter
        courseRows.setOnItemClickListener { _, _, position, _ ->
            if (position != selectedCourse) {
                courseDialog?.dismiss()
                onSelectCourse(listedCourses[position], snapshot())
            }
        }
        var refreshArrange: (() -> Unit)? = null
        fun paintCourseList() {
            val more = canLoadMoreCourses()
            courseHeading.text = if (more) "课程列表 · 已加载 ${listedCourses.size} 节"
            else "课程列表 · ${listedCourses.size} 节"
            moreCourses.visibility = if (more) View.VISIBLE else View.GONE
            moreCourses.isEnabled = !loadingCourses
            moreCourses.text = if (loadingCourses) "正在加载…" else "加载更多"
            previous.isEnabled = selectedCourse > 0
            previous.alpha = if (previous.isEnabled) 1f else 0.35f
            next.isEnabled = selectedCourse in 0 until listedCourses.lastIndex
            next.alpha = if (next.isEnabled) 1f else 0.35f
            courseAdapter.notifyDataSetChanged()
            refreshArrange?.invoke()
        }
        fun replaceCourses(updated: List<PanEntry>) {
            listedCourses = updated
            selectedCourse = updated.indexOfFirst {
                it.origin == entry.origin && it.fileId == entry.fileId &&
                    (it.isDisk || (it.gid == entry.gid && it.messageId == entry.messageId))
            }
        }
        sortCourses.setOnClickListener { anchor ->
            onSortCourses(anchor) { updated, label ->
                if (dialog.isShowing) {
                    replaceCourses(updated)
                    sortCourses.text = label
                    sortCourses.contentDescription = "课程排序，当前 ${label.substringBefore(' ')}"
                    paintCourseList()
                    if (selectedCourse >= 0) courseRows.setSelection((selectedCourse - 1).coerceAtLeast(0))
                }
            }
        }
        moreCourses.setOnClickListener {
            loadingCourses = true
            paintCourseList()
            onLoadMoreCourses { updated ->
                loadingCourses = false
                if (!dialog.isShowing) return@onLoadMoreCourses
                if (updated != null) {
                    replaceCourses(updated)
                } else {
                    Toast.makeText(activity, "加载失败，请重试", Toast.LENGTH_SHORT).show()
                }
                paintCourseList()
            }
        }
        paintCourseList()
        if (selectedCourse >= 0) courseRows.setSelection((selectedCourse - 1).coerceAtLeast(0))
        fun showCourseDialog() {
            val holder = FrameLayout(activity)
            embeddedCourses.removeView(coursePanel)
            val rows = listedCourses.size.coerceIn(1, 6)
            val dialogHeight = minOf(
                (video.height * 0.72f).toInt(),
                dp(52) + dp(52) * rows + dp(24),
            )
            holder.addView(coursePanel, FrameLayout.LayoutParams(-1, dialogHeight))
            video.controllerShowTimeoutMs = 0
            val sheet = AlertDialog.Builder(dialog.context)
                .setView(holder)
                .setNegativeButton("关闭", null)
                .create()
            courseDialog = sheet
            sheet.setOnDismissListener {
                courseDialog = null
                holder.removeView(coursePanel)
                embeddedCourses.addView(coursePanel, FrameLayout.LayoutParams(-1, -1))
                video.controllerShowTimeoutMs = 3_000
            }
            sheet.show()
            sheet.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFFBFC6FF.toInt())
            if (selectedCourse >= 0) courseRows.setSelection((selectedCourse - 1).coerceAtLeast(0))
        }
        val sleepHandler = Handler(Looper.getMainLooper())
        val timer = actionCell(R.drawable.ic_timer, "定时", "睡眠定时") {}
        fun paintTimer() {
            val armed = SleepTimer.isArmed()
            timer.icon.setColorFilter(if (armed) actionActiveColor else Color.WHITE)
            timer.label.setTextColor(if (armed) actionActiveColor else actionLabelColor)
            val remaining = SleepTimer.remainingMs()
            timer.root.contentDescription = if (armed) {
                "睡眠定时，剩余 ${formatAudioTime((remaining / 1000).toInt())}"
            } else {
                "睡眠定时"
            }
        }
        val sleepTick = object : Runnable {
            override fun run() {
                if (!SleepTimer.isArmed()) {
                    paintTimer()
                    return
                }
                if (SleepTimer.remainingMs() <= 0L) {
                    SleepTimer.disarm()
                    player.pause()
                    paintTimer()
                } else {
                    sleepHandler.postDelayed(this, 1_000L)
                }
            }
        }
        fun showTimerMenu(anchor: View) {
            val menu = PopupMenu(activity, anchor, Gravity.END)
            menu.menu.add(0, 0, 0, "关闭定时").isChecked = !SleepTimer.isArmed()
            listOf(15, 30, 45, 60).forEachIndexed { index, minutes ->
                menu.menu.add(0, index + 1, index + 1, "$minutes 分钟")
            }
            menu.menu.add(0, 99, 99, "本集结束后停止")
            menu.menu.setGroupCheckable(0, true, false)
            menu.setOnMenuItemClickListener { item ->
                sleepHandler.removeCallbacks(sleepTick)
                when (item.itemId) {
                    0 -> SleepTimer.disarm()
                    99 -> SleepTimer.armStopAfterCurrent()
                    else -> {
                        SleepTimer.arm(listOf(15, 30, 45, 60)[item.itemId - 1])
                        sleepHandler.postDelayed(sleepTick, 1_000L)
                    }
                }
                paintTimer()
                true
            }
            menu.setOnDismissListener { video.controllerShowTimeoutMs = 3_000 }
            video.controllerShowTimeoutMs = 0
            menu.show()
        }
        timer.root.setOnClickListener { showTimerMenu(it) }
        paintTimer()
        // 横屏没有课程列表，选集做成控制行右端的文字胶囊；竖屏列表常驻，不再重复放入口
        val courseChip = TextView(activity).apply {
            text = "选集"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_video_chip)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            setOnClickListener { showCourseDialog() }
        }
        val subtitle = TextView(activity).apply {
            val folder = PanApi.readablePath(entry.path)
                .substringBeforeLast('/', "").substringAfterLast('/')
            text = listOfNotNull(
                folder.takeIf { it.isNotEmpty() },
                "${listedCourses.size} 节".takeIf { listedCourses.isNotEmpty() },
            ).joinToString(" · ").ifBlank { "视频" }
            setTextColor(0xFFB4B7C4.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val titleColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        }
        val moreButton = iconButton(R.drawable.ic_more_horiz, "更多", padding = 12) {}
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_video_scrim_top)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            addView(
                iconButton(R.drawable.ic_arrow_back, "返回") { handleBack() },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
            addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(audioOnly, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(moreButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        // ···：只放低频动作；竖屏下方已有课程列表，不再放「选集」，转音频已常驻顶栏
        moreButton.setOnClickListener { anchor ->
            val menu = PopupMenu(dialog.context, anchor, Gravity.END)
            menu.menu.add(0, 0, 0, if (favorite.label.text == "已收藏") "取消收藏" else "收藏")
            menu.menu.add(0, 1, 1, "睡眠定时")
            menu.menu.add(0, 2, 2, "文件信息")
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> favorite.root.performClick()
                    1 -> showTimerMenu(anchor)
                    2 -> AlertDialog.Builder(dialog.context)
                        .setTitle(entry.name)
                        .setMessage(PanApi.readablePath(entry.path))
                        .setPositiveButton("关闭", null)
                        .show()
                }
                true
            }
            video.controllerShowTimeoutMs = 0
            menu.setOnDismissListener { video.controllerShowTimeoutMs = 3_000 }
            menu.show()
        }
        val playbackStatus = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(message, LinearLayout.LayoutParams(-2, -2))
            addView(retry, LinearLayout.LayoutParams(-2, -2))
        }
        // 缓冲提示居中在画面里；音频态单独挂，避免共用容器的边距把文案裁掉
        val stageOverlay = FrameLayout(activity).apply {
            addView(playbackStatus, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val content = FrameLayout(activity).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(stageOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(audioStage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
                // 顶栏是浮层（约 64dp），单行控制条叠在画面底部（约 36dp）。
                // 把音频态限制在两者之间居中，避免圆形被顶栏压住、文案被控制条盖住。
                topMargin = dp(68)
                bottomMargin = dp(44)
            })
        }
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            if (Build.VERSION.SDK_INT >= 30) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    insets
                }
            }
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(embeddedCourses, FrameLayout.LayoutParams(-1, -1))
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        // 兜底到 PlayerView 自身：万一以后换布局少了 content_frame，也不至于整个播放器崩掉
        val videoFrame = video.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: video
        var controllerVisible = true
        val progressRow = video.findViewById<LinearLayout>(R.id.videoProgressRow)
        // 控制层只有一行：传输组 · 当前时间 · 轨道 · 时长 · 倍速 · 选集(横屏) · 全屏。
        // 全部挂在 PlayerView 的 bottom bar 内，叠在 16:9 画面内底部，不占布局高度。
        styleTransport(true)
        progressRow.addView(transport, 0, LinearLayout.LayoutParams(-2, dp(36)))
        progressRow.addView(speed, LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(6) })
        progressRow.addView(courseChip, LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(6) })
        progressRow.addView(fullscreen, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) })
        fun arrangePlayer() {
            val top = 0
            val availableWidth = root.width - root.paddingLeft - root.paddingRight
            val availableHeight = root.height - root.paddingTop - root.paddingBottom - top
            val landscape = immersive || availableWidth > availableHeight + top
            val hasCourses = listedCourses.isNotEmpty()
            val inlineCourses = !landscape && hasCourses
            val sideCourses = !immersive && landscape && availableWidth >= dp(EXPANDED_WINDOW_DP) && hasCourses
            val sideWidth = if (sideCourses) dp(320) else 0
            // 画面按 16:9 铺满 stage；控制层叠在画面内底部，不预留任何高度。
            // 课程列表固定从画面下沿开始，显隐都不改变占位 → 无空白。
            val stageHeight = if (inlineCourses && availableWidth > 0 && availableHeight > 0) {
                minOf(availableWidth * 9 / 16, (availableHeight * 0.58f).toInt())
            } else -1
            val stage = content.layoutParams as FrameLayout.LayoutParams
            if (stage.height != stageHeight || stage.topMargin != top || stage.rightMargin != sideWidth) {
                stage.height = stageHeight
                stage.topMargin = top
                stage.rightMargin = sideWidth
                content.layoutParams = stage
            }
            val frame = videoFrame.layoutParams as FrameLayout.LayoutParams
            if (frame.bottomMargin != 0) {
                frame.bottomMargin = 0
                videoFrame.layoutParams = frame
            }

            val list = embeddedCourses.layoutParams as FrameLayout.LayoutParams
            val showInline = inlineCourses && stageHeight > 0
            val listTop = if (showInline) top + stageHeight else top
            val listWidth = if (sideCourses) sideWidth else -1
            if (list.topMargin != listTop || list.width != listWidth) {
                list.topMargin = listTop
                list.width = listWidth
                list.gravity = Gravity.RIGHT
                embeddedCourses.layoutParams = list
            }
            val showEmbeddedCourses = showInline || sideCourses
            if (showEmbeddedCourses && courseDialog?.isShowing == true) courseDialog?.dismiss()
            embeddedCourses.visibility = if (showEmbeddedCourses) View.VISIBLE else View.GONE
            courseChip.visibility = if (landscape && hasCourses && !sideCourses) View.VISIBLE else View.GONE
            audioOnly.visibility = if (immersive) View.GONE else View.VISIBLE
            // 竖屏宽度有限：传输组只留播放键，上一/下一交给下方常驻课程列表；
            // 横屏够宽，三件全显示。避免时间/轨道/倍速被挤到重叠。
            previous.visibility = if (landscape) View.VISIBLE else View.GONE
            next.visibility = if (landscape) View.VISIBLE else View.GONE
            fullscreen.contentDescription = when {
                immersive -> "退出全屏"
                shouldRotateForFullscreen() -> "横屏全屏"
                else -> "全屏"
            }
        }
        refreshArrange = { arrangePlayer() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> arrangePlayer() }
        arrangePlayer()
        // ── 手势（仅全屏态生效）：左右滑进度 / 双击播放暂停 / 长按倍速 / 左亮度·右音量 ──
        val audioManager = activity.getSystemService(android.media.AudioManager::class.java)
        val hint = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            // 固定最小宽度：文案变短时视图不缩，避免残留上一帧的像素
            minWidth = dp(200)
            setBackgroundResource(R.drawable.bg_video_chip)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        // 放在上方：中央位置是「正在缓冲…」提示，两者会叠在一起
        content.addView(
            hint,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(76) },
        )
        val hideHint = Runnable { hint.visibility = View.GONE }
        fun showHint(text: String) {
            hint.text = text
            hint.visibility = View.VISIBLE
            hint.removeCallbacks(hideHint)
            hint.postDelayed(hideHint, 900)
        }
        var gestureMode = 0        // 0=未定 1=进度 2=亮度/音量
        var downX = 0f
        var downY = 0f
        var seekBase = 0L
        var seekTarget = 0L
        var brightBase = -1f
        var volumeBase = -1
        var maxVolume = 1
        var gestureSide = 0        // 1=左半屏亮度 2=右半屏音量
        var speedBase = 1f
        var speedHeld = false
        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            // 手势层吃掉了触摸，PlayerView 自己不会再切控制条，这里补上单击显隐。
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controllerVisible) video.hideController() else video.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                    player.play()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                gestureMode = 0
                speedBase = player.playbackParameters.speed
                speedHeld = true
                player.setPlaybackParameters(PlaybackParameters(2f))
                paintSpeed()
                showHint("松开恢复 ${PlaybackSpeed.label(speedBase)}")
            }
        })
        // 位移用原始坐标差计算（不走 GestureDetector.onScroll）：快速滑动会被识别成 fling，
        // onScroll 提前终止，累计距离会严重偏小。
        val gestureLayer = View(activity).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        gestureMode = 0
                        gestureSide = 0
                        brightBase = -1f
                        volumeBase = -1
                        speedHeld = false
                    }

                    MotionEvent.ACTION_MOVE -> if (!speedHeld) {
                        val width = video.width.coerceAtLeast(1)
                        val height = video.height.coerceAtLeast(1)
                        val totalX = event.x - downX
                        val totalY = event.y - downY
                        if (gestureMode == 0 && (abs(totalX) >= dp(10) || abs(totalY) >= dp(10))) {
                            gestureMode = if (abs(totalX) > abs(totalY)) 1 else 2
                            seekBase = player.currentPosition
                            seekTarget = seekBase
                            if (gestureMode == 2) {
                                gestureSide = if (downX < width / 2f) 1 else 2
                                val current = dialog.window?.attributes?.screenBrightness ?: -1f
                                brightBase = if (current > 0f) current else 0.5f
                                volumeBase = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            }
                        }
                        if (gestureMode == 1) {
                            val duration = player.duration
                            if (duration > 0) {
                                seekTarget = (seekBase + (totalX / width * duration)).toLong().coerceIn(0L, duration)
                                val sign = if (seekTarget >= seekBase) "+" else "-"
                                val delta = abs(seekTarget - seekBase) / 1000
                                val ss = (delta % 60).toString().padStart(2, '0')
                                showHint("$sign${delta / 60}:$ss  ${formatAudioTime((seekTarget / 1000).toInt())}")
                            }
                        } else if (gestureMode == 2) {
                            // 上滑增大，下滑减小
                            val ratio = (-totalY / height).coerceIn(-1f, 1f)
                            if (gestureSide == 1) {
                                val value = (brightBase + ratio).coerceIn(0.01f, 1f)
                                dialog.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value } }
                                showHint("亮度 ${(value * 100).toInt()}%")
                            } else {
                                val value = (volumeBase + (ratio * maxVolume).toInt()).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, value, 0)
                                showHint("音量 ${value * 100 / maxVolume}%")
                            }
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (gestureMode == 1 && player.duration > 0) player.seekTo(seekTarget)
                        if (player.playbackParameters.speed != speedBase) {
                            player.setPlaybackParameters(PlaybackParameters(speedBase))
                            paintSpeed()
                        }
                        gestureMode = 0
                        speedHeld = false
                    }
                }
                gestureDetector.onTouchEvent(event)
            }
        }
        videoFrame.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        video.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            controllerVisible = visibility == View.VISIBLE
            // 布局恒定（控制层叠加、不占位），显隐不再触发重排。
            // 竖屏保留顶栏（Dialog 没有系统返回入口），横屏才完全沉浸。
            topBar.visibility = if (immersive) visibility else View.VISIBLE
        })
        var orientationRequested = startFullscreen && activity.requestedOrientation != orientationBeforeFullscreen
        updateFullscreen = { enabled ->
            immersive = enabled
            arrangePlayer()
            fullscreen.setImageResource(if (enabled) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
            gestureLayer.visibility = if (enabled) View.VISIBLE else View.GONE
            if (!enabled) hint.visibility = View.GONE
            video.controllerShowTimeoutMs = 3_000
            if (enabled && shouldRotateForFullscreen()) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                orientationRequested = true
            } else if (!enabled && orientationRequested) {
                activity.requestedOrientation = orientationBeforeFullscreen
                orientationRequested = false
            }
            updateSystemBars()
            video.showController()
            topBar.visibility = View.VISIBLE
        }
        dialog.setContentView(root)
        val checkpoint = object : Runnable {
            override fun run() {
                saveProgress()
                video.postDelayed(this, 5_000)
            }
        }
        dialog.setOnDismissListener {
            courseDialog?.dismiss()
            stopAudioSession()
            if (orientationRequested && !keepOrientationOnClose && !activity.isDestroyed && !activity.isFinishing) {
                activity.requestedOrientation = orientationBeforeFullscreen
            }
            sleepHandler.removeCallbacks(sleepTick)
            video.removeCallbacks(checkpoint)
            saveProgress()
            video.player = null
            player.release()
            onClose(this)
        }
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            private var rendered = false

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    paintPlayPause()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                video.removeCallbacks(checkpoint)
                if (isPlaying) {
                    video.postDelayed(checkpoint, 5_000)
                    ensureAudioSession()
                } else if (player.playbackState != Player.STATE_ENDED) saveProgress()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioOnly.isEnabled = tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                audioOnly.alpha = if (audioOnly.isEnabled) 1f else 0.35f
                if (!tracks.isEmpty && listening && !audioOnly.isEnabled) setListening(false)
            }

            override fun onRenderedFirstFrame() {
                rendered = true
                message.visibility = View.GONE
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    readyToSave = true
                    ensureAudioSession()
                }
                if (playbackState == Player.STATE_ENDED) {
                    stopAudioSession()
                    progress.edit().remove(progressKey).remove(legacyProgressKey).apply()
                    history.save(entry, (player.currentPosition / 1000).toInt(), completed = true, duration = durationSeconds())
                    readyToSave = false
                    video.post {
                        if (!canAutoAdvance()) return@post
                        if (SleepTimer.consumeStopAfterCurrent()) return@post
                        onEnded(snapshot())
                    }
                }
                if (player.playerError != null) return
                message.visibility = if (!listening && (!rendered || playbackState == Player.STATE_BUFFERING)) {
                    View.VISIBLE
                } else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                stopAudioSession()
                val status = error.mediaHttpStatus()
                message.text = "播放失败（${status?.let { "HTTP $it" } ?: error.errorCodeName}）。"
                message.visibility = View.VISIBLE
                retry.visibility = View.VISIBLE
                if (retryOnExpiredLink &&
                    (error.isExpiredMediaLink() || (entry.provider == SourceProvider.QUARK && status == 412))
                ) {
                    video.post { if (dialog.isShowing) retryPlayback(true) }
                }
            }
        })
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::handleBack,
            )
        }
        if (startFullscreen) updateFullscreen(true)
        player.setMediaSource(source, startPositionMs ?: savedPositionMs())
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    data class Snapshot(
        val entry: PanEntry,
        val account: String,
        val positionMs: Long?,
        val playWhenReady: Boolean,
        val audioOnly: Boolean,
        val fullscreen: Boolean,
        val orientationBeforeFullscreen: Int,
    )

    fun canAutoAdvance(): Boolean =
        dialog.isShowing && player.playbackState == Player.STATE_ENDED && player.playWhenReady

    fun snapshot() = Snapshot(
        entry, account, player.currentPosition, player.playWhenReady, listening, immersive, orientationBeforeFullscreen,
    )

    fun pause() {
        player.pause()
        saveProgress()
    }

    fun close(keepOrientation: Boolean = false) {
        keepOrientationOnClose = keepOrientation
        dialog.dismiss()
    }

    private fun savedPositionMs(): Long {
        val saved = progress.getLong(progressKey, 0L)
        if (saved > 0 || entry.isDisk) return saved
        // 旧版本进度键不含来源；首次打开时迁移一次，避免升级后丢失续播位置。
        val legacy = progress.getLong(legacyProgressKey, 0L)
        if (legacy > 0) {
            progress.edit().putLong(progressKey, legacy).remove(legacyProgressKey).apply()
        }
        return legacy
    }

    private fun saveProgress() {
        if (!readyToSave) return
        val position = player.currentPosition
        progress.edit().putLong(progressKey, position).apply()
        history.save(entry, (position / 1000).toInt(), completed = false, duration = durationSeconds())
    }

    private fun durationSeconds(): Int {
        val ms = player.duration
        return if (ms > 0) (ms / 1000).toInt() else 0
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        val window = dialog.window ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (immersive) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else 0
        }
    }

    private fun iconButton(
        iconRes: Int,
        description: String,
        padding: Int = 10,
        action: () -> Unit,
    ) = ImageView(activity).apply {
        setImageResource(iconRes)
        setColorFilter(Color.WHITE)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        setBackgroundResource(R.drawable.bg_video_icon_ripple)
        isClickable = true
        setOnClickListener { action() }
    }

    private fun button(label: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_video_chip)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isClickable = true
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}

@OptIn(UnstableApi::class)
internal fun VideoStream.mediaSource(item: MediaItem): MediaSource {
    val http = DefaultHttpDataSource.Factory().setUserAgent(PanApi.DLINK_USER_AGENT)
    return HlsMediaSource.Factory { type ->
        if (type == C.DATA_TYPE_MANIFEST) HlsManifestDataSource(this, http)
        else http.createDataSource()
    }.createMediaSource(item)
}

internal fun PlaybackException.mediaHttpStatus(): Int? =
    generateSequence(cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode

internal fun PlaybackException.isExpiredMediaLink(): Boolean =
    mediaHttpStatus() in setOf(401, 403, 404, 410)

@OptIn(UnstableApi::class)
internal class HlsManifestDataSource(
    private val stream: VideoStream,
    private val http: DataSource.Factory,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var source: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        source?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val delegate = if (dataSpec.uri == stream.uri) ByteArrayDataSource(stream.playlist)
        else http.createDataSource()
        source = delegate
        listeners.forEach(delegate::addTransferListener)
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(source).read(buffer, offset, length)

    override fun getUri(): Uri? = source?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()

    override fun close() {
        val delegate = source
        source = null
        delegate?.close()
    }
}
