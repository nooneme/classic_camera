package com.classic.camera

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.FileObserver
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class FilterActivity : AppCompatActivity() {

    private data class FilterItem(val name: String, val file: File?)

    private val filterList = mutableListOf<FilterItem>()
    private lateinit var listView: ListView
    private lateinit var tvEmptyHint: TextView
    private var adapter: FilterAdapter? = null
    private var fileObserver: FileObserver? = null

    /** 当前选中的滤镜路径（空串=无） */
    private var currentFilterPath: String = ""

    private val noneItem = FilterItem("无", null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filter)

        currentFilterPath = intent.getStringExtra("current_filter") ?: ""

        listView = findViewById(R.id.listFilters)
        tvEmptyHint = findViewById(R.id.tvEmptyHint)

        adapter = FilterAdapter()
        listView.adapter = adapter

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val item = filterList[position]
            val data = Intent().putExtra("filter_path", item.file?.absolutePath ?: "")
            setResult(RESULT_OK, data)
            finish()
        }

        findViewById<Button>(R.id.btnNewFilter).setOnClickListener {
            startActivity(Intent(this, LearnFilterActivity::class.java))
        }

        findViewById<Button>(R.id.btnApplyFilter).setOnClickListener {
            startActivity(Intent(this, ApplyFilterActivity::class.java))
        }

        setupFileObserver()
    }

    private fun setupFileObserver() {
        val dir = getExternalFilesDir("filters") ?: filesDir
        dir.mkdirs()

        fileObserver = object : FileObserver(dir.absolutePath, CREATE or DELETE or MOVED_FROM or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.endsWith(".cube")) {
                    runOnUiThread { loadFilters() }
                }
            }
        }
        fileObserver?.startWatching()
    }

    override fun onResume() {
        super.onResume()
        loadFilters()
    }

    override fun onPause() {
        super.onPause()
        fileObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        fileObserver?.stopWatching()
        fileObserver = null
    }

    private fun loadFilters() {
        filterList.clear()
        filterList.add(noneItem)
        val dir = getExternalFilesDir("filters") ?: filesDir
        if (dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".cube") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { filterList.add(FilterItem(it.nameWithoutExtension, it)) }
        }
        tvEmptyHint.visibility = View.GONE
        adapter?.notifyDataSetChanged()
    }

    private inner class FilterAdapter : BaseAdapter() {
        override fun getCount() = filterList.size
        override fun getItem(pos: Int) = filterList[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@FilterActivity)
                .inflate(R.layout.item_filter, parent, false)
            val item = filterList[pos]
            val isSelected = (item.file?.absolutePath ?: "") == currentFilterPath
            view.setBackgroundColor(if (isSelected) 0xFF3A6EA5.toInt() else 0xFF222222.toInt())
            view.findViewById<TextView>(R.id.tvFilterName).text = item.name
            view.findViewById<TextView>(R.id.tvFilterName).setTextColor(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
            )
            val tvSize = view.findViewById<TextView>(R.id.tvFilterSize)
            val btnDelete = view.findViewById<TextView>(R.id.btnDelete)
            val btnVisualize = view.findViewById<TextView>(R.id.btnVisualize)
            if (item.file != null) {
                tvSize.text = formatFileSize(item.file.length())
                tvSize.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener { confirmDelete(item, pos) }
                btnVisualize.visibility = View.VISIBLE
                btnVisualize.setOnClickListener {
                    VectorFieldRenderer.pendingLut = LutUtils.loadCubeFile(item.file)
                    startActivity(Intent(this@FilterActivity, VectorFieldActivity::class.java)
                        .putExtra("filter_name", item.name))
                }
            } else {
                tvSize.text = ""
                tvSize.visibility = View.INVISIBLE
                btnDelete.visibility = View.GONE
                btnVisualize.visibility = View.GONE
            }
            return view
        }

        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            }
        }
    }

    private fun confirmDelete(item: FilterItem, pos: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除滤镜")
            .setMessage("确定删除「${item.name}」？")
            .setPositiveButton("删除") { _, _ ->
                item.file?.delete()
                filterList.removeAt(pos)
                adapter?.notifyDataSetChanged()
                if (filterList.size <= 1) tvEmptyHint.visibility = View.VISIBLE
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
