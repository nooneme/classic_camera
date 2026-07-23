package com.classic.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class LutSelectorAdapter(
    private val onItemClick: (LutEntry) -> Unit,
    private val onPhase1Complete: (() -> Unit)? = null
) : RecyclerView.Adapter<LutSelectorAdapter.ViewHolder>() {

    data class LutEntry(
        val name: String,
        val file: File?,
        val lutData: FloatArray?,
        val thumbnail: Bitmap?
    )

    private val items = mutableListOf<LutEntry>()
    private val noneEntry = LutEntry("无", null, null, null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    var selectedPath: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun currentSelectedIndex(): Int {
        return items.indexOfFirst { (it.file?.absolutePath ?: "") == selectedPath }
    }

    init {
        items.add(noneEntry)
    }

    fun loadFromDirectory(dir: File) {
        // Phase 1: 立即显示文件列表（仅名称，无数据）
        LutThumbnail.resetSession()
        val currentPath = selectedPath

        val nameOnly = mutableListOf<LutEntry>()
        nameOnly.add(noneEntry)

        if (dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".cube") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    nameOnly.add(LutEntry(file.nameWithoutExtension, file, null, null))
                }
        }

        val initialPath = if (nameOnly.any { it.file?.absolutePath == currentPath }) currentPath else ""
        items.clear()
        items.addAll(nameOnly)
        selectedPath = initialPath
        notifyDataSetChanged()
        onPhase1Complete?.invoke()

        // Phase 2: 后台逐个加载 LUT 数据 + 缩略图，每加载完一个就更新
        bgExecutor.execute {
            for (i in 1 until nameOnly.size) {
                val entry = nameOnly[i]
                val file = entry.file ?: continue
                val lutData = LutUtils.loadCubeFile(file)
                val thumb = if (lutData != null) LutThumbnail.generate(lutData, 36) else null
                val loaded = entry.copy(lutData = lutData, thumbnail = thumb)

                mainHandler.post {
                    val idx = items.indexOfFirst { it.file?.absolutePath == file.absolutePath }
                    if (idx >= 0) {
                        items[idx] = loaded
                        notifyItemChanged(idx)
                    }
                }
            }
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lut_selector, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val isSelected = (entry.file?.absolutePath ?: "") == selectedPath

        holder.name.text = entry.name
        holder.name.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())

        if (entry.thumbnail != null) {
            holder.thumb.setImageBitmap(entry.thumbnail)
        } else {
            holder.thumb.setImageBitmap(null)
            holder.thumb.setBackgroundColor(0xFF2A2A2A.toInt())
        }

        holder.selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.checkMark.visibility = if (isSelected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            // 点击时如果 LUT 数据还没加载完，在主线程同步加载
            var data = entry.lutData
            if (data == null && entry.file != null) {
                data = LutUtils.loadCubeFile(entry.file)
                val idx = items.indexOfFirst { it.file?.absolutePath == entry.file.absolutePath }
                if (idx >= 0) {
                    val thumb = LutThumbnail.generate(data!!, 36)
                    items[idx] = entry.copy(lutData = data, thumbnail = thumb)
                    notifyItemChanged(idx)
                }
            }
            selectedPath = entry.file?.absolutePath ?: ""
            notifyDataSetChanged()
            onItemClick(entry.copy(lutData = data))
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivLutThumb)
        val name: TextView = view.findViewById(R.id.tvLutName)
        val selectedOverlay: View = view.findViewById(R.id.selectedOverlay)
        val checkMark: TextView = view.findViewById(R.id.tvCheckMark)
    }
}
