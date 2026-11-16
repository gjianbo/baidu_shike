package com.hnwd.bdplayer

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat

internal class FileSelectionBar(
    private val view: View,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
) {
    private val count = view.findViewById<TextView>(R.id.fileSelectionCount)
    private val selectAll = view.findViewById<View>(R.id.fileSelectAll)
    private val mark = view.findViewById<ImageView>(R.id.fileSelectAllMark)

    init {
        view.findViewById<View>(R.id.fileSelectionClose).setOnClickListener { onClose() }
        selectAll.setOnClickListener { onToggleAll() }
    }

    fun render(active: Boolean, selected: Int, available: Int) {
        view.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) return
        val allSelected = available > 0 && selected == available
        count.text = view.context.getString(R.string.file_selection_count, selected)
        selectAll.isEnabled = available > 0
        mark.paintSelectionCircle(allSelected)
        ViewCompat.setStateDescription(selectAll, if (allSelected) "已全选" else "未全选")
    }
}
