package com.hnwd.bdplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.net.toUri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Resolve a fresh link using the selected provider's current session. */
internal object DownloadLinkResolver {
    data class Link(val uri: Uri, val cookie: String? = null)

    /**
     * 一次取址的结果：单条 URL（DownloadManager 能直接吃），
     * 或一份 HLS 分片清单（群组视频原文件直链拿不到时的唯一通路，见 [GroupVideoDownload]）。
     */
    sealed class Resolution {
        data class Single(val link: Link) : Resolution()
        data class Playlist(val plan: HlsPlan) : Resolution()
    }

    class VerificationRequired(val payload: JSONObject) :
        IOException("百度网盘需要验证码，请在传输中心点击验证")
    class Failure(message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    /** 自动转存会往网盘写文件：同一时刻只允许一笔在飞，避免并发任务互相误清副本。 */
    private val prefetchGate = AtomicBoolean(false)
    /** 同一进程内每个下载任务只尝试一次转存，防止 requeue / 进程恢复反复写盘。 */
    private val prefetchTried = mutableSetOf<Long>()

    /** 转存落盘轮询：最多 6 轮 × 1.2s ≈ 7.2s；超时就落回 HLS，不长时间占着并发槽。 */
    private const val LOG_TAG = "ShikeLink"
    private const val TRANSFER_WAIT_ROUNDS = 6
    private const val TRANSFER_POLL_MS = 1_200L

    fun resolve(
        context: Context, task: DownloadTask, session: TransferSession = TransferSession.capture(task.provider),
        verification: String = "",
    ): Resolution {
        val entry = task.entry ?: throw Failure("下载记录缺少文件信息，请回到文件列表重新下载")
        if (session.provider != task.provider || entry.provider != task.provider) {
            throw Failure("下载记录与当前网盘账号不匹配，请重新添加任务")
        }
        val ua = task.userAgent
        return when (DownloadLogic.routeOf(entry)) {
            DownloadRoute.QUARK -> {
                TransferSession.requireAccount(task.provider, task.viewer, session.quarkAccount())
                val drive = QuarkPersonalDrive.forTransfer(session, ua)
                val link = try {
                    drive.downloadForTransfer(QuarkFile(entry.fileId, entry.name, false))
                } catch (error: Exception) {
                    if (error.message?.contains("23018") == true) {
                        throw Failure("当前夸克账号无法下载此文件（23018）")
                    }
                    throw Failure("无法获取夸克下载地址，请检查登录和网络后重试")
                }
                Resolution.Single(link)
            }
            DownloadRoute.BAIDU_GROUP -> {
                val viewer = currentViewer(ua, session.cookie)
                TransferSession.requireAccount(task.provider, task.viewer, viewer)
                val payload = json(
                    PanApi.mediaLinkRequest(), ua, session.cookie,
                    PanApi.mediaLinkBody(entry, viewer) + verification,
                )
                // 群组大视频的 /api/sharedownload 会把 list 换成加密串（网页端同样拿不到
                // 原文件地址）。parseMediaLink 的 check 会抛 IllegalStateException，若不包装，
                // 下载中心会兜底成「请检查网络后重试」，把网盘服务端限制误报成网络故障。
                val direct = runCatching { PanApi.parseMediaLink(payload, entry) }
                val uri = direct.getOrElse {
                    val issue = DownloadLinkLogic.groupLinkIssue(payload, entry.fileId)
                    val isVideo = PanApi.kindOf(entry) == MediaKind.VIDEO
                    // 二级通路会**往用户网盘写文件**，准入必须最严：只有确认是
                    // 「群组视频 + list 被加密」才值得花一次转存额度换原始文件。
                    // 非视频、空 list、fs_id 不匹配、无 dlink 一律不碰网盘。
                    if (DownloadLinkLogic.shouldTryPrefetch(isVideo, issue)) {
                        prefetchOriginal(task.id, context, entry, ua, session.cookie)?.let { return it }
                    }
                    // 原文件直链拿不到时，视频改走 HLS 转码流（真实字节、分片自带签名）；
                    // 文档/图片/音频没有转码流，仍按服务端限制如实报错（准入条件在纯逻辑层）。
                    if (DownloadLinkLogic.shouldTryPlaylist(isVideo, issue)) {
                        val plan = try {
                            GroupVideoDownload.plan(
                                entry, ua, session.cookie, VideoQualityStore.get(context),
                            )
                        } catch (error: Exception) {
                            throw Failure(
                                DownloadLinkLogic.failureText(issue) + "（转码流也不可用：" +
                                    (error.message ?: "未知原因") + "）",
                            )
                        }
                        return Resolution.Playlist(plan)
                    }
                    throw Failure(DownloadLinkLogic.failureText(issue))
                }
                Resolution.Single(Link(uri))
            }
            DownloadRoute.BAIDU_DISK -> {
                val account = json(PanApi.diskDownloadSession(includeAccount = true), ua, session.cookie)
                    .getJSONObject("result")
                TransferSession.requireAccount(task.provider, task.viewer, account.getString("uk"))
                val script = diskSignScript(
                    account.getString("sign1"), account.getString("sign2"), account.getString("sign3"),
                )
                val sign = evaluateSignature(context, script)
                val url = PanApi.diskDownload(
                    entry, account.getString("bdstoken"), sign,
                    account.getString("timestamp"), account.getString("is_vip"),
                )
                val raw = PanApi.parseDiskDownloadLink(json(url + verification, ua, session.cookie))
                Resolution.Single(Link(probeDiskLink(raw, ua, session.cookie)))
            }
        }
    }

    /**
     * 二级通路「转存拿原始文件」：`sharedownload` 拿不到直链后，把群组视频转存到我的网盘，
     * 再按网盘文件的完整取址流程拿原始直链。准入在 [DownloadLinkLogic.shouldTryPrefetch]，
     * 这里只负责「怎么安全地写、怎么安全地收拾」。
     *
     * 实测（`docs/百度网盘转存后下载可行性-实测结论.md`）：
     * - 小文件转存后 `/api/download` 给 `expires=8h` 的 dlink，带 Cookie 直接 302 到 CDN，能下；
     * - 大视频给 `expires=1h` 且 dstime 偏早 2 小时的 dlink，服务端 31360（签发即过期）。
     *
     * 不按文件大小预判（阈值不可靠），拿到 dlink 后用 `inspectDlink` 判形状；
     * 不可用就删掉刚转存的副本，无缝落到 HLS 兜底。
     *
     * 写盘三道保险：同一任务只试一次、同一时刻只有一笔在飞、只认「目录快照之后新增」
     * 的条目。任何一步拿不到可信基线都直接放弃转存，绝不带着模糊匹配去动用户网盘。
     */
    private fun prefetchOriginal(
        taskId: Long, context: Context, entry: PanEntry, ua: String, cookie: String,
    ): Resolution? {
        // 网盘写操作没有事务：并发的两个下载任务各自拿一份目录快照时，
        // 会把对方刚写入的副本当成「新增垃圾」清掉。下载侧先做互斥。
        if (!prefetchGate.compareAndSet(false, true)) return null
        return try {
            // requeue / drain() 恢复会重新取址，不能每次都往网盘里再转存一份
            // （ondup=newcopy 会堆出 xxx(1)、xxx(2) 一串副本）。
            synchronized(prefetchTried) { if (!prefetchTried.add(taskId)) return null }
            prefetchOriginalLocked(context, entry, ua, cookie)
        } finally {
            prefetchGate.set(false)
        }
    }

    private fun prefetchOriginalLocked(
        context: Context, entry: PanEntry, ua: String, cookie: String,
    ): Resolution? {
        val quota = runCatching {
            GroupTransferLogic2.parseQuota(json(PanApi.quota(), ua, cookie))
        }.getOrNull() ?: return null
        // 唯一真相源：群组条目、非目录、空间够（复用 UI 转存入同一判据）。
        if (!GroupTransferLogic2.canPrefetch(entry, quota.freeBytes)) return null
        val batch = groupTransferBatches(listOf(entry)).firstOrNull() ?: return null
        val token = bdstoken(ua, cookie) ?: return null
        // 提交前拍快照：既保证专用目录存在，也保证后续只认本次新增的条目。
        val baselinePaths = stagingSnapshot(token, ua, cookie)?.map { it.path }?.toSet() ?: return null
        if (!submitTransfer(token, batch, ua, cookie)) return null
        val transferred = awaitTransferred(entry, baselinePaths, token, ua, cookie)
        if (transferred == null) {
            // errno=4（QPS 上限但可能已成功）或异步落盘慢于轮询：副本可能晚一点出现。
            // 只清「本次同名且快照后新增」的条目，避免残留占额度。
            discardNewCopies(token, baselinePaths, entry.name, ua, cookie)
            return null
        }
        val direct = runCatching { diskDirectLink(context, transferred, ua, cookie) }.getOrNull()
        if (direct == null) {
            // 转存已发生但直链不可用（服务端不给 / 探测失败）：删掉刚转存的副本，
            // 否则每次点下载都会在网盘里留一份几十上百 MB 的垃圾。
            discardTransferred(token, transferred, ua, cookie)
            return null
        }
        return Resolution.Single(Link(direct))
    }

    /** 转存提交（`POST /mbox/msg/transfer`）；errno=0 或 4（QPS 上限但可能已成功）都算提交成功。 */
    private fun submitTransfer(
        token: String, batch: GroupTransferBatch, ua: String, cookie: String,
    ): Boolean = runCatching {
        val request = PanApi.transferFromGroup(token, batch, GroupTransferLogic2.STAGING_DIR)
        val data = json(request.url, ua, cookie, request.form.ifBlank { null })
        data.optInt("errno", -1).let { it == 0 || it == GROUP_TRANSFER_LENIENT_ERRNO }
    }.getOrDefault(false)

    /** 列一次专用目录；读不到就返回 null（调用方据此放弃写盘，不带模糊基线继续）。 */
    private fun stagingEntries(token: String, ua: String, cookie: String): List<PanEntry>? = runCatching {
        PanApi.parseDiskList(
            json(PanApi.diskList(token, GroupTransferLogic2.STAGING_DIR, 1), ua, cookie),
        )
    }.getOrNull()

    /** 幂等建专用目录（已存在时接口返非 0 errno，忽略），再取一份可信的目录快照。 */
    private fun stagingSnapshot(token: String, ua: String, cookie: String): List<PanEntry>? {
        runCatching {
            val request = PanApi.createFolder(token, GroupTransferLogic2.STAGING_DIR)
            json(request.url, ua, cookie, request.form.ifBlank { null })
        }
        return stagingEntries(token, ua, cookie)
    }

    /**
     * 转存是 `async=1` 异步落盘：轮询专用目录直到「快照后新增且名字大小匹配」的文件出现。
     * 最多等 [TRANSFER_WAIT_ROUNDS] × [TRANSFER_POLL_MS]，超时返回 null 交给兜底。
     */
    private fun awaitTransferred(
        entry: PanEntry, baselinePaths: Set<String>, token: String, ua: String, cookie: String,
    ): PanEntry? {
        val size = entry.size.toLongOrNull() ?: -1L
        repeat(TRANSFER_WAIT_ROUNDS) { round ->
            if (round > 0) Thread.sleep(TRANSFER_POLL_MS)
            val list = stagingEntries(token, ua, cookie) ?: return@repeat
            GroupTransferLogic2.matchNewlyTransferred(baselinePaths, list, entry.name, size)
                ?.let { return it }
        }
        return null
    }

    /**
     * 清理「转了但没用上」的副本：只删专用目录里本次新增、同名的一条，
     * 删前回读核对 `path -> fs_id`，删后再回读确认。
     * 失败只记日志不抛 —— 清理是尽力而为，不能因为删不掉就把下载判失败。
     */
    private fun discardTransferred(token: String, entry: PanEntry, ua: String, cookie: String) {
        if (!GroupTransferLogic2.isStagingPath(entry)) return
        runCatching {
            val current = stagingEntries(token, ua, cookie) ?: return@runCatching
            val target = current.firstOrNull { it.path == entry.path && it.fileId == entry.fileId }
                ?: return@runCatching
            val request = PanApi.deleteItems(token, listOf(target))
            json(request.url, ua, cookie, request.form.ifBlank { null })
            if (stagingEntries(token, ua, cookie)?.any { it.fileId == target.fileId } == true) {
                android.util.Log.w(LOG_TAG, "transferred copy still present after delete")
            }
        }.onFailure {
            android.util.Log.w(LOG_TAG, "discard transferred copy failed (${it.javaClass.simpleName})")
        }
    }

    /** 轮询超时后的兜底清理：限定同名，别把同一时刻用户自己放进去的文件删掉。 */
    private fun discardNewCopies(
        token: String, baselinePaths: Set<String>, sourceName: String, ua: String, cookie: String,
    ) {
        val list = stagingEntries(token, ua, cookie) ?: return
        list.filter { GroupTransferLogic2.isOwnCopyNamed(it, baselinePaths, sourceName) }
            .forEach { discardTransferred(token, it, ua, cookie) }
    }

    private fun bdstoken(ua: String, cookie: String): String? = runCatching {
        json(PanApi.groupListSession(), ua, cookie).getJSONObject("result").getString("bdstoken")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 网盘文件的原始直链：与「我的网盘 → 下载」完全同一条链路（签 sign1/2/3 + 探测可达性）。
     *
     * 拿到 dlink 后先用 [GroupTransferLogic2.inspectDlink] 判可用性：服务端对不给直链的
     * 文件会把 `dstime` 前移 7200 秒、`expires` 压到 1h，这种链接一定 31360。
     * 在**发请求之前**就认出来，能省掉一次必然失败的探测，也能把「为什么不行」说清楚。
     */
    private fun diskDirectLink(
        context: Context, entry: PanEntry, ua: String, cookie: String,
    ): Uri {
        val account = json(PanApi.diskDownloadSession(includeAccount = true), ua, cookie)
            .getJSONObject("result")
        val script = diskSignScript(
            account.getString("sign1"), account.getString("sign2"), account.getString("sign3"),
        )
        val sign = evaluateSignature(context, script)
        val timestamp = account.getString("timestamp")
        val url = PanApi.diskDownload(
            entry, account.getString("bdstoken"), sign,
            timestamp, account.getString("is_vip"),
        )
        val raw = PanApi.parseDiskDownloadLink(json(url, ua, cookie))
        val shape = GroupTransferLogic2.inspectDlink(
            raw.toString(), timestamp.toLongOrNull() ?: 0L,
        )
        if (shape != null && !shape.usable) {
            throw Failure(
                "网盘未给该文件直链（有效期 ${shape.expires}、时间偏移 ${shape.skewSeconds}s），改走转码流",
            )
        }
        return probeDiskLink(raw, ua, cookie)
    }

    /** viewer() 的 JSON 结构异常单独包装；网络/登录/验证码异常保持原样往外抛。 */
    private fun currentViewer(userAgent: String, cookie: String): String {
        val payload = json(PanApi.viewer(), userAgent, cookie)
        return runCatching { payload.getJSONObject("result").getString("uk") }
            .getOrElse { throw Failure(DownloadLinkLogic.ACCOUNT_ERROR) }
    }

    fun captcha(task: DownloadTask, session: TransferSession, challenge: VerificationRequired): PanApi.MediaCaptcha? =
        PanApi.parseMediaCaptcha(challenge.payload)
            ?: if (DownloadLogic.routeOf(requireNotNull(task.entry)) == DownloadRoute.BAIDU_GROUP) {
                PanApi.parseMediaCaptcha(json(PanApi.mediaCaptchaRequest(), task.userAgent, session.cookie))
            } else null

    private fun json(url: String, userAgent: String, cookie: String, form: String? = null): JSONObject {
        val request = request(url, userAgent, cookie)
            .header("X-Requested-With", "XMLHttpRequest")
        if (form != null) {
            request.post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        }
        client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw Failure("百度网盘取址失败（HTTP ${response.code}）")
            val body = response.body?.string().orEmpty()
            // 风控页 / 登录跳转会返回 HTML，JSONObject 直接抛 JSONException → 之前被
            // 下载中心兜底成「请检查网络」。这里给出真实原因，便于区分登录态与网络。
            val data = runCatching { JSONObject(body) }
                .getOrElse { throw Failure(DownloadLinkLogic.responseError(response.code)) }
            when (val errno = data.optInt("errno", -1)) {
                0 -> return data
                -20 -> throw VerificationRequired(data)
                -6 -> throw Failure("请先登录百度网盘")
                else -> throw Failure("百度网盘取址失败（错误码 $errno）")
            }
        }
    }

    private fun request(url: String, userAgent: String, cookie: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Referer", DownloadLogic.BAIDU_REFERER)
            .header("Cookie", cookie)

    private fun probeDiskLink(uri: Uri, userAgent: String, cookie: String): Uri {
        val probe = request(uri.toString(), userAgent, cookie).header("Range", "bytes=0-0").build()
        client.newCall(probe).execute().use { response ->
            if (response.isRedirect) {
                val target = response.header("Location").orEmpty().toUri()
                if (PanApi.isCachedFileLink(target)) return target
            }
            if (response.code in listOf(200, 206) &&
                response.header("Content-Type").orEmpty().startsWith("application/json", ignoreCase = true).not()
            ) return uri
            val code = if (response.code in 400..499) runCatching {
                JSONObject(response.peekBody(1024).string()).optInt("error_code")
            }.getOrNull()?.takeIf { it != 0 } else null
            // 实测（见 docs/百度网盘转存后下载可行性-实测结论.md）：
            // · 小文件 dlink 是 expires=8h，带 Cookie 直接 302 到 CDN，能下；
            // · 大视频 dlink 是 expires=1h 且 dstime 比签发时间早 2 小时，服务端返回
            //   31360 = expire time out error，签发即过期 —— 换 UA、加 Cookie、绕代理都一样。
            //   所以这是服务端对大文件的限流，不是本地签名错误，重试无效。
            val detail = if (code == null) "HTTP ${response.code}" else "HTTP ${response.code} / $code"
            throw Failure(
                when (code) {
                    // 这个词条对「我的网盘」下载也可见，那里并不会自动改走转码流，
                    // 不能承诺一个不存在的降级动作。
                    31360 -> "网盘对该文件的直链限流（31360 链接已过期），请稍后重试"
                    31045 -> "网盘要求登录后才能下载直链（31045），请确认登录态"
                    else -> "百度网盘拒绝了原文件下载（$detail）"
                },
            )
        }
    }

    /** The same sign2 script as media playback, evaluated with a local page and no network access. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun evaluateSignature(context: Context, script: String): String {
        val result = CompletableFuture<String?>()
        var web: WebView? = null
        main.post {
            if (!result.isDone) {
                runCatching {
                    val view = WebView(context)
                    web = view
                    view.apply {
                        settings.javaScriptEnabled = true
                        settings.blockNetworkLoads = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!result.isDone) view.evaluateJavascript(script) {
                                    result.complete(parseSignResult(it))
                                }
                            }
                        }
                        loadDataWithBaseURL(
                            "https://pan.baidu.com/", "<html></html>", "text/html", "UTF-8", null,
                        )
                    }
                }.onFailure { result.completeExceptionally(it) }
            }
        }
        return try {
            result.get(8, TimeUnit.SECONDS) ?: throw Failure("无法生成百度网盘下载签名")
        } finally {
            result.cancel(false)
            main.post { web?.destroy() }
        }
    }
}
