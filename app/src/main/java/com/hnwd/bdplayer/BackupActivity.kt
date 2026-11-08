package com.hnwd.bdplayer

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : AppCompatActivity() {
    private lateinit var includes: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var countView: TextView

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeExport(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val payload = BackupStore.read(this, uri)
            if (!BackupStore.valid(payload)) {
                toast("备份文件无效或版本不兼容")
            } else {
                confirmRestore(payload!!, "从文件恢复")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.bg)
        window.navigationBarColor = getColor(R.color.bg)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ThemeStore.isDark(this@BackupActivity)
            isAppearanceLightNavigationBars = !ThemeStore.isDark(this@BackupActivity)
        }
        setContentView(R.layout.activity_backup)
        includes = findViewById(R.id.backupIncludes)
        list = findViewById(R.id.backupList)
        countView = findViewById(R.id.backupCount)
        findViewById<View>(R.id.backupBack).setOnClickListener { finish() }
        findViewById<View>(R.id.backupNow).setOnClickListener {
            BackupStore.capture(this, "manual")
            render()
            toast("已写入本地快照 · 保留最近 3 份")
        }
        findViewById<View>(R.id.backupExport).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
            exportLauncher.launch("拾课备份-$stamp.json")
        }
        findViewById<View>(R.id.backupImport).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
        }
        BackupStore.ensureInitial(this)
        render()
    }

    private fun render() {
        val snapshots = BackupStore.list(this)
        countView.text = "${snapshots.size} / 3"
        renderIncludes()
        list.removeAllViews()
        if (snapshots.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "还没有快照。点「立即备份」写入第一份。"
                textSize = 12f
                setTextColor(getColor(R.color.ink3))
                setPadding(dp(16), dp(18), dp(16), dp(18))
            })
            return
        }
        snapshots.forEachIndexed { index, snapshot ->
            if (index > 0) list.addView(divider())
            list.addView(snapshotRow(snapshot))
        }
    }

    private fun renderIncludes() {
        val payload = BackupStore.build(this)
        val data = payload.optJSONObject("data") ?: JSONObject()
        val groups = data.optJSONArray("sources")?.length() ?: 0
        val favorites = runCatching { JSONArray(data.optString("favorites", "[]")).length() }.getOrDefault(0)
        val progress = runCatching {
            var total = 0
            val audio = data.optJSONObject("audioHistory")
            val video = data.optJSONObject("videoHistory")
            listOf(audio, video).forEach { map ->
                if (map != null) {
                    val keys = map.keys()
                    while (keys.hasNext()) {
                        total += runCatching { JSONArray(map.optString(keys.next(), "[]")).length() }.getOrDefault(0)
                    }
                }
            }
            total
        }.getOrDefault(0)
        includes.removeAllViews()
        val groupId = data.optString("groupId")
        val groupName = data.optJSONArray("sources")?.let { stored ->
            (0 until stored.length()).mapNotNull { stored.optJSONObject(it) }
                .firstOrNull { it.optString("id") == groupId }?.optString("name")
        }.orEmpty().ifBlank { groupId.ifBlank { "—" } }
        addInclude("群组来源", "$groups 个 · 当前 $groupName")
        addInclude("收藏", "$favorites 项")
        addInclude("播放进度", "$progress 条")
        addInclude("偏好", "外观、排序")
    }

    private fun addInclude(label: String, value: String) {
        includes.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            addView(ImageView(this@BackupActivity).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(getColor(R.color.ok))
                setBackgroundColor(getColor(R.color.okSoft))
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(TextView(this@BackupActivity).apply {
                text = label
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.ink))
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@BackupActivity).apply {
                text = value
                textSize = 11.5f
                setTextColor(getColor(R.color.ink3))
            })
        }.also { row ->
            if (includes.childCount > 0) {
                // 分隔线由下一行顶部承担；保持简单无额外 View
            }
        })
    }

    private fun snapshotRow(snapshot: BackupStore.Snapshot): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(12))
        addView(ImageView(this@BackupActivity).apply {
            setImageResource(R.drawable.ic_archive)
            setColorFilter(getColor(R.color.accentInk))
            setBackgroundResource(R.drawable.bg_empty_icon)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(LinearLayout(this@BackupActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(LinearLayout(this@BackupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@BackupActivity).apply {
                    text = BackupStore.timeLabel(snapshot.time)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.ink))
                })
                addView(TextView(this@BackupActivity).apply {
                    text = if (snapshot.kind == "manual") "手动" else "自动"
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(if (snapshot.kind == "manual") R.color.folderInk else R.color.accentInk))
                    setBackgroundResource(if (snapshot.kind == "manual") R.drawable.bg_tag_manual else R.drawable.bg_tag_auto)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
            })
            addView(TextView(this@BackupActivity).apply {
                text = snapshot.summary
                textSize = 10.5f
                setTextColor(getColor(R.color.ink3))
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@BackupActivity).apply {
            text = "恢复"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.accentInk))
            setBackgroundResource(R.drawable.bg_source_chip)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { confirmRestore(snapshot.payload, "快照 ${BackupStore.timeLabel(snapshot.time)}") }
        })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(66) }
    }

    private fun confirmRestore(payload: JSONObject, label: String) {
        AlertDialog.Builder(this)
            .setTitle("恢复这份备份？")
            .setMessage(
                "$label\n${BackupStore.summary(payload)}\n\n" +
                    "将覆盖群组来源与收藏，并合并播放进度；登录态与播放地址不在备份内。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                if (BackupStore.restore(this, payload)) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    toast("备份文件无效或版本不兼容")
                }
            }
            .show()
    }

    private fun writeExport(uri: Uri) {
        runCatching {
            val payload = BackupStore.build(this)
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload.toString(2)) }
            toast("已导出 JSON")
        }.onFailure { toast("导出失败：${it.message}") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
