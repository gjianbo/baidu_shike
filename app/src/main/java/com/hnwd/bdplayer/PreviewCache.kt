package com.hnwd.bdplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

internal enum class PreviewCacheLimit(val megabytes: Int, val label: String) {
    SMALL(200, "200 MB"),
    DEFAULT(500, "500 MB"),
    LARGE(1024, "1 GB");

    val bytes: Long get() = megabytes * 1024L * 1024L
}

/** 图片原图和 PDF 共用额度。文件位置沿用旧版本，FileProvider 路径也适用。 */
internal class PreviewCache(
    private val directory: File,
    private val settings: SharedPreferences? = null,
) {
    enum class Kind(val directoryName: String, val tempPrefix: String, val tempSuffix: String) {
        IMAGE("images", "image-", ".tmp"),
        PDF("pdf", "document-", ".pdf"),
    }

    data class Usage(
        val imageBytes: Long,
        val pdfBytes: Long,
        val inUseBytes: Long,
        val limit: PreviewCacheLimit,
    ) {
        val totalBytes: Long get() = imageBytes + pdfBytes
    }

    private val references = mutableMapOf<File, Int>()
    private var limit = PreviewCacheLimit.entries.firstOrNull {
        it.megabytes == settings?.getInt(LIMIT_KEY, PreviewCacheLimit.DEFAULT.megabytes)
    } ?: PreviewCacheLimit.DEFAULT
    private var trimQueued = false
    private var trimOnRelease = false

    /** 命中时同时持有文件，避免清理发生在查到文件和使用文件之间。 */
    @Synchronized
    fun acquire(kind: Kind, name: String): File? {
        val file = File(directory(kind), name)
        if (kind == Kind.IMAGE && file.length() <= 12L) return null
        return file.takeIf(::retain)
    }

    @Synchronized
    fun retain(file: File): Boolean {
        if (!file.isFile) return false
        references[file] = references.getOrDefault(file, 0) + 1
        file.setLastModified(System.currentTimeMillis())
        return true
    }

    @Synchronized
    fun release(file: File) {
        val count = references[file] ?: return
        if (count > 1) {
            references[file] = count - 1
            return
        }
        references.remove(file)
        if (trimOnRelease) scheduleTrim()
    }

    @Synchronized
    fun target(kind: Kind): File {
        val folder = directory(kind).apply { mkdirs() }
        return File.createTempFile(kind.tempPrefix, kind.tempSuffix, folder).also {
            references[it] = 1
        }
    }

    @Synchronized
    fun discard(temp: File) {
        references.remove(temp)
        temp.delete()
    }

    /** 调用方持有返回值，直至完成预览或把文件交给查看器。 */
    @Synchronized
    fun publish(kind: Kind, name: String, temp: File): File {
        acquire(kind, name)?.let {
            discard(temp)
            return it
        }
        val file = File(directory(kind), name)
        val failure = if (kind == Kind.IMAGE) "无法保存图片缓存，请重试。" else "无法保存文档缓存，请重试。"
        check(temp.renameTo(file)) { failure }
        references.remove(temp)
        retain(file)
        scheduleTrim()
        return file
    }

    @Synchronized
    fun scheduleTrim() {
        if (trimQueued) return
        trimQueued = true
        AppIo.execute {
            synchronized(this) {
                try {
                    trim()
                } finally {
                    trimQueued = false
                }
            }
        }
    }

    @Synchronized
    fun setLimit(value: PreviewCacheLimit): Usage {
        settings?.edit { putInt(LIMIT_KEY, value.megabytes) }
        limit = value
        trim()
        return usage()
    }

    @Synchronized
    fun usage(): Usage {
        val images = files(Kind.IMAGE)
        val pdfs = files(Kind.PDF)
        return Usage(
            imageBytes = images.sumOf(File::length),
            pdfBytes = pdfs.sumOf(File::length),
            inUseBytes = (images + pdfs).filter { it in references }.sumOf(File::length),
            limit = limit,
        )
    }

    @Synchronized
    fun clear(): Usage {
        Kind.entries.flatMap(::files).filterNot { it in references }.forEach(File::delete)
        return usage()
    }

    private fun trim() {
        val cached = mutableListOf<File>()
        for (kind in Kind.entries) {
            for (file in files(kind)) {
                if (!file.name.startsWith(kind.tempPrefix)) {
                    cached.add(file)
                } else if (file !in references) {
                    // 临时文件从创建到发布一直有引用，失去引用后无法续传。
                    file.delete()
                }
            }
        }
        var total = cached.sumOf(File::length)
        if (total > limit.bytes) {
            val candidates = cached.filterNot { it in references }.sortedBy(File::lastModified)
            for (file in candidates) {
                if (total <= limit.bytes) break
                val size = file.length()
                if (file.delete() || !file.exists()) total -= size
            }
        }
        trimOnRelease = total > limit.bytes
    }

    private fun directory(kind: Kind) = File(directory, kind.directoryName)

    private fun files(kind: Kind): List<File> =
        directory(kind).listFiles().orEmpty().filter {
            it.isFile && (kind != Kind.PDF || it.extension == "pdf")
        }

    companion object {
        private const val LIMIT_KEY = "preview_cache_limit_mb"
        private var instance: PreviewCache? = null

        @Synchronized
        fun get(context: Context): PreviewCache {
            val app = context.applicationContext
            return instance ?: PreviewCache(
                app.cacheDir,
                app.getSharedPreferences("app_settings", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
