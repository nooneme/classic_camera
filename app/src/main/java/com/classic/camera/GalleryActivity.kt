package com.classic.camera

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_CODE = 100
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: TextView

    private data class FolderInfo(val bucketId: Long, val name: String, val coverId: Long, val count: Int)
    private data class PhotoInfo(val id: Long, val uri: Uri, val dateTaken: Long, val name: String)

    private val folders = mutableListOf<FolderInfo>()
    private val photos = mutableListOf<PhotoInfo>()
    private var currentBucketName: String? = null

    private val folderAdapter = FolderAdapter { folder ->
        currentBucketName = folder.name
        loadPhotos(folder.bucketId)
    }
    private val photoAdapter = PhotoAdapter { photo ->
        Intent().apply {
            data = photo.uri
            setResult(RESULT_OK, this)
        }
        finish()
    }

    private val executor = Executors.newFixedThreadPool(4)
    private val thumbCache = LruCache<Long, Bitmap>(200)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySelectedTheme()
        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.recyclerView)
        tvTitle = findViewById(R.id.tvTitle)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener {
            if (currentBucketName != null) {
                showFolders()
            } else {
                finish()
            }
        }

        checkPermission()
    }

    private fun checkPermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE

        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            showFolders()
        } else {
            requestPermissions(arrayOf(perm), PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showFolders()
            } else {
                Toast.makeText(this, "需要存储权限才能读取图片", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun showFolders() {
        currentBucketName = null
        tvTitle.text = "选择图片"
        btnBack.text = "‹ 返回"
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = folderAdapter
        loadFolders()
    }

    private fun loadFolders() {
        executor.execute {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media._ID
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            val bucketMap = linkedMapOf<Long, Pair<String, Long>>()
            val countMap = mutableMapOf<Long, Int>()

            contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val bucketId = cursor.getLong(0)
                    val bucketName = cursor.getString(1) ?: "未知"
                    val imageId = cursor.getLong(2)

                    countMap[bucketId] = (countMap[bucketId] ?: 0) + 1
                    if (!bucketMap.containsKey(bucketId)) {
                        bucketMap[bucketId] = Pair(bucketName, imageId)
                    }
                }
            }

            folders.clear()
            bucketMap.forEach { (id, pair) ->
                folders.add(FolderInfo(id, pair.first, pair.second, countMap[id] ?: 0))
            }

            mainHandler.post { folderAdapter.notifyDataSetChanged() }
        }
    }

    private fun loadPhotos(bucketId: Long) {
        executor.execute {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            photos.clear()
            contentResolver.query(uri, projection, selection, arrayOf(bucketId.toString()), sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val dateTaken = cursor.getLong(1)
                    val name = cursor.getString(2) ?: ""
                    val photoUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    photos.add(PhotoInfo(id, photoUri, dateTaken, name))
                }
            }

            mainHandler.post {
                tvTitle.text = currentBucketName ?: "选择图片"
                btnBack.text = "‹ 相册"
                recyclerView.layoutManager = GridLayoutManager(this@GalleryActivity, 3)
                recyclerView.adapter = photoAdapter
                photoAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadThumbnail(imageId: Long, imageView: ImageView) {
        thumbCache.get(imageId)?.let {
            imageView.setImageBitmap(it)
            return
        }
        executor.execute {
            val bmp = try {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    contentResolver, imageId, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            } catch (e: Exception) { null }
            if (bmp != null) {
                thumbCache.put(imageId, bmp)
                mainHandler.post { imageView.setImageBitmap(bmp) }
            }
        }
    }

    private inner class FolderAdapter(private val onClick: (FolderInfo) -> Unit) :
        RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivCover: ImageView = view.findViewById(R.id.ivCover)
            val tvName: TextView = view.findViewById(R.id.tvFolderName)
            val tvCount: TextView = view.findViewById(R.id.tvPhotoCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_folder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            holder.tvName.text = folder.name
            holder.tvCount.text = "${folder.count} 张"
            loadThumbnail(folder.coverId, holder.ivCover)
            holder.itemView.setOnClickListener { onClick(folder) }
        }

        override fun getItemCount() = folders.size
    }

    private inner class PhotoAdapter(private val onClick: (PhotoInfo) -> Unit) :
        RecyclerView.Adapter<PhotoAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivPhoto: ImageView = view.findViewById(R.id.ivPhoto)
            val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_photo, parent, false)
            val size = parent.width / 3
            view.layoutParams.height = size
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val photo = photos[position]
            loadThumbnail(photo.id, holder.ivPhoto)
            holder.tvFileName.text = photo.name
            holder.itemView.setOnClickListener { onClick(photo) }
        }

        override fun getItemCount() = photos.size
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
