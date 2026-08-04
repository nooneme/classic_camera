package com.classic.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val context: Context,
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

    private val baseThumbnail: Bitmap? by lazy {
        try {
            val bmp = BitmapFactory.decodeStream(context.assets.open("lut_thumb_base.jpg"))
            Bitmap.createScaledBitmap(bmp, 36, 36, true).also { if (bmp != it) bmp.recycle() }
        } catch (e: Exception) { null }
    }
    private val noneEntry = LutEntry("无", null, null, null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val pendingLoads = mutableSetOf<String>()

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
        // 保留已加载的数据/缩略图，只对新出现且未加载的文件补加载，避免每次回到前台都清空重载
        LutThumbnail.resetSession()
        val currentPath = selectedPath
        val existing = items
            .filter { it.file != null }
            .associateBy { it.file!!.absolutePath }

        val list = mutableListOf<LutEntry>()
        list.add(noneEntry)

        if (dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".cube") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    val ex = existing[file.absolutePath]
                    if (ex != null) {
                        list.add(ex)
                    } else {
                        // 优先同步读取磁盘缓存的缩略图，避免进入页面后先空白再异步加载
                        val cachedThumb = LutThumbnail.cachedThumbnail(context, file)
                        list.add(LutEntry(file.nameWithoutExtension, file, null, cachedThumb))
                    }
                }
        }

        val initialPath = if (list.any { it.file?.absolutePath == currentPath }) currentPath else ""
        items.clear()
        items.addAll(list)
        selectedPath = initialPath
        notifyDataSetChanged()
        onPhase1Complete?.invoke()

        // 只加载尚未有数据的条目（且不在加载队列中，避免重复排队）
        val toLoad = list.filter {
            it.file != null && it.lutData == null && it.file!!.absolutePath !in pendingLoads
        }
        if (toLoad.isEmpty()) return
        toLoad.forEach { it.file?.let { f -> pendingLoads.add(f.absolutePath) } }

        // Phase 2: 后台逐个加载 LUT 数据 + 缩略图，每加载完一个就更新
        bgExecutor.execute {
            for (entry in toLoad) {
                val file = entry.file ?: continue
                val lutData = LutUtils.loadCubeFile(file)
                val thumb = if (lutData != null) LutThumbnail.generate(lutData, 36, context, file) else null
                val loaded = entry.copy(lutData = lutData, thumbnail = thumb)

                mainHandler.post {
                    pendingLoads.remove(file.absolutePath)
                    val idx = items.indexOfFirst { it.file?.absolutePath == file.absolutePath }
                    // 仅在目标项仍未加载时更新，避免旧任务覆盖已加载的新数据
                    if (idx >= 0 && items[idx].lutData == null) {
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
        holder.name.setTextColor(if (isSelected)
            holder.itemView.context.getAttrColor(R.attr.textPrimary)
        else
            holder.itemView.context.getAttrColor(R.attr.textSecondary))

        if (entry.thumbnail != null) {
            holder.thumb.setImageBitmap(entry.thumbnail)
        } else if (entry.file == null) {
            holder.thumb.setImageBitmap(baseThumbnail)
        } else {
            holder.thumb.setImageBitmap(null)
            holder.thumb.setBackgroundColor(holder.itemView.context.getAttrColor(R.attr.surfaceLight))
        }

        holder.thumbCard.strokeWidth = if (isSelected)
            holder.itemView.resources.getDimensionPixelSize(R.dimen.border_medium)
        else
            0
        holder.thumbCard.strokeColor = if (isSelected)
            holder.itemView.context.getAttrColor(R.attr.accentColor)
        else
            0

        holder.itemView.setOnClickListener {
            // 点击时如果 LUT 数据还没加载完，在主线程同步加载
            var data = entry.lutData
            if (data == null && entry.file != null) {
                data = LutUtils.loadCubeFile(entry.file)
                val idx = items.indexOfFirst { it.file?.absolutePath == entry.file.absolutePath }
                if (idx >= 0) {
                    val thumb = LutThumbnail.generate(data!!, 36, context, entry.file)
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
        val thumbCard: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.thumbCard)
    }
}
