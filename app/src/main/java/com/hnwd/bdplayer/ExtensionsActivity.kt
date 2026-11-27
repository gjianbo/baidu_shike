package com.hnwd.bdplayer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.concurrent.TimeUnit

class ExtensionsActivity : AppCompatActivity() {
    private enum class SettingsPage(val id: String, val title: String) {
        DOWNLOAD("download", "下载设置"),
        SITE_SESSIONS("site_sessions", "平台登录与 Cookie"),
    }

    private data class Extension(
        val packageName: String,
        val title: String,
        val description: String,
        val minimumVersion: Long,
        val release: ExtensionReleaseSource,
        val settingsPages: List<SettingsPage> = emptyList(),
        val legacySettingsThroughVersion: Long = 0,
    )

    private val extensions = listOf(
        Extension(
            ExtensionDownload.PACKAGE, "分享下载", "在设备上下载视频并管理平台会话", 1,
            ExtensionReleaseSource("gjianbo/baidu_shike_share_down", "baidu-shike-share-download-arm64.apk"),
            settingsPages = listOf(SettingsPage.DOWNLOAD, SettingsPage.SITE_SESSIONS),
            legacySettingsThroughVersion = 2,
        ),
    )
    private val collectorPreferences = object : CollectorPreferencesHost {
        override fun settings() = getSharedPreferences("account_scope", MODE_PRIVATE)
        override fun accountVerified() = runCatching { collectorAccount(SourceProvider.BAIDU).isNotEmpty() }.getOrDefault(false)
        override fun quarkLoggedIn() = QuarkPersonalDrive.openSession() != null
    }

    private fun collectorAccount(provider: SourceProvider): String = when (provider) {
        SourceProvider.BAIDU -> {
            val account = intent.getStringExtra(BAIDU_ACCOUNT).orEmpty()
            if (account.isEmpty() || account != AccountScope.current) "" else {
                TransferSession.capture(provider)
                account
            }
        }
        SourceProvider.QUARK -> TransferSession.capture(provider).quarkAccount()
    }

    private val apkDirectory: File get() = File(cacheDir, "update/extensions")
    private lateinit var list: LinearLayout
    private var pendingApk: String? = null
    private lateinit var updateButton: View
    private var releaseDialog: AlertDialog? = null

    private val allowInstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val name = pendingApk
        pendingApk = null
        if (name != null) {
            val apk = File(apkDirectory, name)
            if (packageManager.canRequestPackageInstalls()) install(apk)
            else {
                say("允许拾课安装应用后可继续安装扩展")
            }
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingApk = savedInstanceState?.getString("pendingApk")
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            val light = !ThemeStore.isDark(this@ExtensionsActivity)
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        val root = centeredContent(layoutInflater.inflate(R.layout.activity_extensions, null))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        list = findViewById(R.id.extensionList)
        updateButton = findViewById(R.id.extensionsUpdates)
        updateButton.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                for (extension in extensions) {
                    val installed = installedPackage(this@ExtensionsActivity, extension.packageName) ?: continue
                    if (!hasAppSignature(this@ExtensionsActivity, installed)) continue
                    menu.add("检查${extension.title}更新").setOnMenuItemClickListener {
                        preparation.checkRelease(extension)
                        true
                    }
                }
            }.show()
        }
        preparation.state.observe(this) { state ->
            when (state) {
                is ApkPreparation.Ready -> {
                    preparation.state.value = null
                    state.result.onSuccess(::install).onFailure(::showError)
                }
                is ApkPreparation.ReleaseChecked -> state.result.fold(
                    onSuccess = { confirmDownload(state.extension, it) },
                    onFailure = {
                        preparation.state.value = null
                        showError(it)
                    },
                )
                else -> Unit
            }
            render()
        }
        findViewById<View>(R.id.extensionsBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        releaseDialog?.setOnDismissListener(null)
        releaseDialog?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingApk", pendingApk)
    }

    private fun render() {
        list.removeAllViews()
        val operation = preparation.state.value
        val busy = operation != null || pendingApk != null
        var canCheckUpdates = false
        extensions.forEach { extension ->
            val row = layoutInflater.inflate(R.layout.item_extension, list, false)
            val installed = installedPackage(this, extension.packageName)
            val trusted = installed?.takeIf { hasAppSignature(this, it) }
            val compatible = trusted != null && trusted.longVersionCode >= extension.minimumVersion
            val staged = if (installed == null || trusted != null) {
                stagedApk(apkDirectory, extension.packageName, trusted?.longVersionCode ?: 0)
            } else null
            if (compatible) canCheckUpdates = true
            val status = when {
                operation is ApkPreparation.Working && operation.packageName == extension.packageName -> operation.message
                staged != null -> "已下载，待安装"
                installed == null -> "未安装"
                trusted == null -> "签名不匹配，请先移除当前扩展"
                !compatible -> "版本过旧"
                else -> installed.versionName?.let { "已安装 · $it" } ?: "已安装"
            }
            row.findViewById<TextView>(R.id.extensionTitle).text = extension.title
            row.findViewById<TextView>(R.id.extensionDescription).text = extension.description
            row.findViewById<TextView>(R.id.extensionStatus).text = status
            val pages = if (compatible) availablePages(extension, checkNotNull(trusted)) else emptyList()
            row.findViewById<TextView>(R.id.extensionOnline).apply {
                val label = when {
                    staged != null -> "继续安装"
                    installed == null -> "下载安装"
                    trusted != null && !compatible -> "更新"
                    pages.isNotEmpty() -> "设置"
                    else -> null
                }
                visibility = if (label == null) View.GONE else View.VISIBLE
                text = label
                isEnabled = !busy
                setOnClickListener { anchor ->
                    when {
                        staged != null -> {
                            install(staged)
                            render()
                        }
                        !compatible -> preparation.checkRelease(extension)
                        else -> showSettings(anchor, pages)
                    }
                }
            }
            list.addView(row)
        }
        updateButton.visibility = if (canCheckUpdates) View.VISIBLE else View.GONE
        updateButton.isEnabled = !busy
    }

    private fun availablePages(extension: Extension, installed: PackageInfo): List<SettingsPage> {
        val ids = settingsPageIds(installed.packageName)
        return when {
            ids != null -> ids.mapNotNull { id -> extension.settingsPages.firstOrNull { it.id == id } }
            installed.longVersionCode <= extension.legacySettingsThroughVersion -> extension.settingsPages
            else -> emptyList()
        }
    }

    private fun showSettings(anchor: View, pages: List<SettingsPage>) {
        if (pages.size == 1) {
            openSettings(pages.single())
            return
        }
        PopupMenu(this, anchor).apply {
            for (page in pages) {
                menu.add(page.title).setOnMenuItemClickListener {
                    openSettings(page)
                    true
                }
            }
        }.show()
    }

    private fun openSettings(page: SettingsPage) {
        when (page) {
            SettingsPage.DOWNLOAD -> showCollectorSettings(collectorPreferences, ::collectorAccount)
            SettingsPage.SITE_SESSIONS -> startActivity(Intent(this, CollectorSitesActivity::class.java))
        }
    }

    @Suppress("DEPRECATION")
    private fun settingsPageIds(packageName: String): List<String>? =
        runCatching {
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.hnwd.bdplayer.SETTINGS_PAGES_V1")
                ?.split(',')?.map(String::trim)?.distinct()
        }.getOrNull()

    private fun confirmDownload(extension: Extension, release: ExtensionRelease) {
        if (releaseDialog?.isShowing == true) return
        val installed = installedPackage(this, extension.packageName)
        if (installed != null) {
            val current = installed.versionName?.let(::parseAppVersion)
            val latest = parseAppVersion(release.version)
            val message = when {
                !hasAppSignature(this, installed) -> "当前扩展的签名不同，请先移除该扩展"
                installed.versionName == release.version -> "已安装最新版本 ${release.version}"
                current != null && latest != null && current > latest -> "已安装更高版本 ${installed.versionName}"
                else -> null
            }
            if (message != null) {
                preparation.state.value = null
                say(message)
                return
            }
        }
        val size = Formatter.formatFileSize(this, release.bytes)
        releaseDialog = AlertDialog.Builder(this)
            .setTitle("下载${extension.title}")
            .setMessage("版本 ${release.version} · $size")
            .setNegativeButton("取消", null)
            .setPositiveButton("下载并安装") { _, _ ->
                preparation.download(applicationContext, apkDirectory, extension, release)
            }
            .create()
            .apply {
                setOnDismissListener {
                    releaseDialog = null
                    if (!isChangingConfigurations && preparation.state.value is ApkPreparation.ReleaseChecked) {
                        preparation.state.value = null
                    }
                }
                show()
            }
    }

    private fun showError(error: Throwable) {
        say(if (error is IllegalStateException) error.message.orEmpty() else "无法获取安装包，请重试")
    }

    private fun install(apk: File) {
        when (val result = UpdateManager.install(this, apk)) {
            UpdateManager.InstallResult.Launched -> Unit
            UpdateManager.InstallResult.NeedPermission -> {
                pendingApk = apk.name
                val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", packageName, null))
                runCatching { allowInstall.launch(settings) }.onFailure {
                    pendingApk = null
                    say("无法打开安装权限设置")
                }
            }
            is UpdateManager.InstallResult.Failed -> say(result.message)
        }
    }

    private class ApkPreparation {
        sealed interface State
        data class Working(val packageName: String, val message: String) : State
        data class ReleaseChecked(val extension: Extension, val result: Result<ExtensionRelease>) : State
        data class Ready(val result: Result<File>) : State

        val state = MutableLiveData<State?>()
        fun checkRelease(extension: Extension) {
            state.value = Working(extension.packageName, "正在查询发布版本…")
            AppIo.execute {
                state.postValue(ReleaseChecked(extension, runCatching { ExtensionReleaseClient.github.latest(extension.release) }))
            }
        }

        fun download(app: Context, directory: File, extension: Extension, release: ExtensionRelease) {
            state.value = Working(extension.packageName, "正在下载…")
            AppIo.execute {
                val staged = runCatching {
                    check(directory.mkdirs() || directory.isDirectory) { "无法创建安装缓存" }
                    val apk = File(directory, "partial-${extension.packageName}-${release.version}.apk")
                    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                    directory.listFiles()?.filter { it != apk && it.isFile && it.lastModified() < cutoff }
                        ?.forEach { it.delete() }
                    ExtensionReleaseClient.github.download(release, apk) { percent ->
                        state.postValue(Working(extension.packageName, "正在下载 $percent%"))
                    }
                    try {
                        val archive = app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
                            ?: error("下载的文件不是有效的 APK")
                        check(archive.packageName == extension.packageName) { "安装包与「${extension.title}」不匹配" }
                        check(archive.versionName == release.version) { "安装包版本与发布信息不一致" }
                        check(UpdateManager.matchesAppSignature(app, archive)) { "安装包的签名与当前拾课不一致" }
                        val installed = installedPackage(app, extension.packageName)
                        check(installed == null || hasAppSignature(app, installed)) { "当前扩展的签名不同，请先移除该扩展" }
                        val minimum = maxOf(extension.minimumVersion, installed?.longVersionCode ?: 0)
                        check(archive.longVersionCode >= minimum) { "发布的安装包版本过旧" }
                        val verified = File(directory, "${extension.packageName}-${archive.longVersionCode}.apk")
                        check(apk.renameTo(verified)) { "无法保存安装包，请重试" }
                        verified
                    } catch (error: Exception) {
                        apk.delete()
                        throw error
                    }
                }
                state.postValue(Ready(staged))
            }
        }
    }

    companion object {
        internal const val BAIDU_ACCOUNT = "baidu_account"

        // Retain downloads when the user leaves and reopens extension management.
        private val preparation = ApkPreparation()

        private fun stagedApk(directory: File, packageName: String, installedVersion: Long): File? {
            val prefix = "$packageName-"
            return directory.listFiles()?.asSequence()?.mapNotNull { file ->
                if (!file.isFile || !file.name.startsWith(prefix) || file.extension != "apk") return@mapNotNull null
                val version = file.nameWithoutExtension.removePrefix(prefix).toLongOrNull() ?: return@mapNotNull null
                if (version <= installedVersion) return@mapNotNull null
                version to file
            }?.maxByOrNull { it.first }?.second
        }

        private fun installedPackage(context: Context, packageName: String): PackageInfo? = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        private fun hasAppSignature(context: Context, info: PackageInfo): Boolean =
            runCatching { UpdateManager.matchesAppSignature(context, info) }.getOrDefault(false)
    }

    private fun say(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
