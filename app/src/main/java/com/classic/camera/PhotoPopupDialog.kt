package com.classic.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class PhotoPopupDialog : DialogFragment() {

    var onPhotoDeleted: (() -> Unit)? = null

    private var photoUris = listOf<Uri>()
    private var currentIndex = 0

    private var confirmDeleteArmed = false
    private var btnDelete: MaterialButton? = null
    private val executor = Executors.newFixedThreadPool(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, 0)
        val uris = arguments?.getStringArrayList(ARG_URIS) ?: return
        photoUris = uris.map { Uri.parse(it) }
        currentIndex = arguments?.getInt(ARG_INDEX, 0) ?: 0
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setDimAmount(0.6f)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                (resources.displayMetrics.heightPixels * 0.78).toInt()
            )
            setGravity(Gravity.CENTER)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_photo_popup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)
        val tvExif1 = view.findViewById<TextView>(R.id.tvExifLine1)
        val tvExif2 = view.findViewById<TextView>(R.id.tvExifLine2)
        btnDelete = view.findViewById<MaterialButton>(R.id.btnDelete)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnCopy)

        if (photoUris.isEmpty()) {
            dismiss()
            return
        }

        viewPager.adapter = PhotoAdapter(photoUris)
        viewPager.setCurrentItem(currentIndex, false)
        updateExif(tvExif1, tvExif2, currentIndex)

        btnDelete?.setOnClickListener {
            if (confirmDeleteArmed) {
                deleteCurrentPhoto(viewPager, tvExif1, tvExif2)
            } else {
                armDeleteConfirm()
            }
        }
        btnCopy.setOnClickListener {
            resetDeleteConfirm()
            copyCurrentPhoto()
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                resetDeleteConfirm()
                updateExif(tvExif1, tvExif2, position)
            }
        })
    }

    /** 第一次点击删除：文字变「确认？」并染成 errorColor，等待二次确认。 */
    private fun armDeleteConfirm() {
        confirmDeleteArmed = true
        val btn = btnDelete ?: return
        btn.text = "确认？"
        btn.setTextColor(android.graphics.Color.WHITE)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            requireContext().getAttrColor(R.attr.errorColor)
        )
    }

    /** 恢复删除按钮初始状态。 */
    private fun resetDeleteConfirm() {
        if (!confirmDeleteArmed) return
        confirmDeleteArmed = false
        val btn = btnDelete ?: return
        btn.text = "删除照片"
        btn.setTextColor(requireContext().getAttrColor(R.attr.errorColor))
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.TRANSPARENT
        )
    }

    private fun updateExif(tv1: TextView, tv2: TextView, index: Int) {
        val uri = photoUris.getOrNull(index) ?: return
        tv1.text = ""
        tv2.text = "加载中..."
        executor.execute {
            val info = readExif(uri)
            activity?.runOnUiThread {
                tv1.text = info.first
                tv2.text = info.second
            }
        }
    }

    private fun readExif(uri: Uri): Pair<String, String> {
        val cr = requireContext().contentResolver
        var exifLine1 = ""
        var exifLine2 = ""
        var dateTaken = 0L
        var fileSize = 0L
        var imageWidth = 0
        var imageHeight = 0
        try {
            val projection = arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            cr.query(uri, projection, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    dateTaken = cur.getLong(0)
                    fileSize = cur.getLong(1)
                    imageWidth = cur.getInt(2)
                    imageHeight = cur.getInt(3)
                }
            }
        } catch (_: Exception) {}

        var make = ""
        var model = ""
        var iso = ""
        var fNumber = ""
        var exposure = ""
        var focal = ""
        try {
            cr.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
                model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
                iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) ?: ""
                val f = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                if (f != null) fNumber = "f/${f.toFloatOrNull()?.let { String.format("%.1f", it) } ?: f}"
                val exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                if (exp != null) {
                    val expSec = exp.toDoubleOrNull()
                    exposure = if (expSec != null && expSec > 0) {
                        if (expSec >= 1) "${expSec}s"
                        else "1/${(1.0 / expSec).roundToInt()}"
                    } else "${exp}s"
                }
                val fl = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                if (fl != null) {
                    val flMm = fl.split("/").firstOrNull()?.toFloatOrNull()
                    focal = if (flMm != null) "${flMm.toInt()}mm" else "${fl}mm"
                }
            }
        } catch (_: Exception) {}

        val cameraLabel = if (make.isNotEmpty() && model.isNotEmpty()) {
            if (model.startsWith(make)) model else "$make $model"
        } else if (model.isNotEmpty()) model else ""

        val parts1 = mutableListOf<String>()
        if (cameraLabel.isNotEmpty()) parts1.add(cameraLabel)
        if (fNumber.isNotEmpty()) parts1.add(fNumber)
        if (exposure.isNotEmpty()) parts1.add(exposure)
        if (focal.isNotEmpty()) parts1.add(focal)
        if (iso.isNotEmpty()) parts1.add("ISO $iso")
        exifLine1 = parts1.joinToString("  ·  ")

        val parts2 = mutableListOf<String>()
        if (imageWidth > 0 && imageHeight > 0) {
            parts2.add("${imageWidth}×${imageHeight}")
        }
        if (fileSize > 0) {
            val sizeStr = when {
                fileSize < 1024 -> "${fileSize}B"
                fileSize < 1024 * 1024 -> "${fileSize / 1024}KB"
                else -> String.format("%.1fMB", fileSize / (1024.0 * 1024.0))
            }
            parts2.add(sizeStr)
        }
        if (dateTaken > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault())
            parts2.add(sdf.format(Date(dateTaken)))
        } else if (imageWidth <= 0 && fileSize <= 0) {
            parts2.add("无法读取图片信息")
        }
        exifLine2 = parts2.joinToString("  ·  ")
        return Pair(exifLine1, exifLine2)
    }

    private fun loadBitmap(uri: Uri, imageView: ImageView) {
        executor.execute {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                activity?.runOnUiThread {
                    imageView.setImageBitmap(bmp)
                }
            } catch (_: Exception) {}
        }
    }

    private fun copyCurrentPhoto() {
        val uri = photoUris.getOrNull(currentIndex) ?: return
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newUri(requireContext().contentResolver, "photo", uri))
            Toast.makeText(context, "已复制到剪切板", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCurrentPhoto(viewPager: ViewPager2, tv1: TextView, tv2: TextView) {
        val uri = photoUris.getOrNull(currentIndex) ?: return
        try {
            requireContext().contentResolver.delete(uri, null, null)
            Toast.makeText(context, "照片已删除", Toast.LENGTH_SHORT).show()
            onPhotoDeleted?.invoke()
            val newList = photoUris.toMutableList()
            newList.removeAt(currentIndex)
            if (newList.isEmpty()) {
                dismiss()
                return
            }
            resetDeleteConfirm()
            val newIdx = currentIndex.coerceAtMost(newList.size - 1)
            photoUris = newList
            currentIndex = newIdx
            viewPager.adapter = PhotoAdapter(newList)
            viewPager.setCurrentItem(newIdx, false)
            updateExif(tv1, tv2, newIdx)
        } catch (e: Exception) {
            Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class PhotoAdapter(private val items: List<Uri>) :
        RecyclerView.Adapter<PhotoAdapter.ViewHolder>() {

        inner class ViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val iv = AppCompatImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { this@PhotoPopupDialog.dismiss() }
            }
            return ViewHolder(iv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.imageView.setImageBitmap(null)
            loadBitmap(items[position], holder.imageView)
        }

        override fun getItemCount() = items.size
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    companion object {
        private const val ARG_URIS = "photo_uris"
        private const val ARG_INDEX = "photo_index"

        fun newInstance(uris: List<String>, index: Int): PhotoPopupDialog {
            return PhotoPopupDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_URIS, ArrayList(uris))
                    putInt(ARG_INDEX, index)
                }
            }
        }
    }
}
