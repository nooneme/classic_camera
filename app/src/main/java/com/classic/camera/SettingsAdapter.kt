package com.classic.camera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.classic.camera.databinding.ItemSettingCurveBinding
import com.classic.camera.databinding.ItemSettingMultiframeBinding
import com.classic.camera.databinding.ItemSettingSliderBinding
import com.classic.camera.databinding.ItemSettingSwitchBinding
import com.classic.camera.databinding.ItemSettingThemeBinding

/**
 * 设置弹窗的 RecyclerView 适配器：按 [SettingsItem.type] 分发到 5 种 item 布局，
 * 支持拖拽重排（[sortMode] 为 true 时显示拖拽手柄）。
 *
 * 所有「把值作用到 pipeline / 保存 prefs」的逻辑都通过 [listener] 回传给宿主，
 * 本适配器只负责渲染与交互分发。
 */
class SettingsAdapter(
    private val items: MutableList<SettingsItem>,
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun getSelectedTheme(): String
        fun onSwitchChanged(item: SettingsItem, checked: Boolean)
        fun onSliderLive(item: SettingsItem, value: Float)
        fun onSliderCommit(item: SettingsItem, value: Float)
        fun onThemeSelected(themeId: String)
        fun onCurveChanged(curve: ToneCurve)
    }

    var sortMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var dialog: AlertDialog? = null
    var dialogBg: Drawable? = null
    private var recycler: RecyclerView? = null

    private val themes: List<ThemeEntry>
    private var selectedTheme: String
    private val onThemeClick: (ThemeEntry) -> Unit

    init {
        // 主题数据（与 MainActivity 展示的一致）
        themes = ALL_THEMES
        selectedTheme = listener.getSelectedTheme()
        onThemeClick = { entry -> listener.onThemeSelected(entry.id) }
    }

    private lateinit var itemTouchHelper: ItemTouchHelper

    init {
        itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recycler: RecyclerView,
                    from: RecyclerView.ViewHolder,
                    to: RecyclerView.ViewHolder
                ): Boolean {
                    val fromPos = from.bindingAdapterPosition
                    val toPos = to.bindingAdapterPosition
                    if (fromPos in items.indices && toPos in items.indices && fromPos != toPos) {
                        val moved = items.removeAt(fromPos)
                        items.add(toPos, moved)
                        notifyItemMoved(fromPos, toPos)
                    }
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun isLongPressDragEnabled() = sortMode
            }
        )
    }

    fun attach(recycler: RecyclerView) {
        this.recycler = recycler
        itemTouchHelper.attachToRecyclerView(recycler)
    }

    fun bindDragHandle(holder: RecyclerView.ViewHolder, handle: View) {
        handle.setOnTouchListener { _, event ->
            if (sortMode) {
                itemTouchHelper.startDrag(holder)
                false
            } else false
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = items[position].type.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (SettingsViewType.values()[viewType]) {            SettingsViewType.SWITCH ->
                SwitchHolder(ItemSettingSwitchBinding.inflate(inflater, parent, false))
            SettingsViewType.MULTIFRAME ->
                MultiFrameHolder(ItemSettingMultiframeBinding.inflate(inflater, parent, false))
            SettingsViewType.SLIDER ->
                SliderHolder(ItemSettingSliderBinding.inflate(inflater, parent, false))
            SettingsViewType.THEME ->
                ThemeHolder(ItemSettingThemeBinding.inflate(inflater, parent, false))
            SettingsViewType.CURVE ->
                CurveHolder(ItemSettingCurveBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item
        bindDragHandle(holder, holder.itemView.findViewById(R.id.dragHandle))
        when (holder) {
            is SwitchHolder -> holder.bind(item)
            is MultiFrameHolder -> holder.bind(item)
            is SliderHolder -> holder.bind(item)
            is ThemeHolder -> holder.bind(item)
            is CurveHolder -> holder.bind(item)
        }
    }

    inner class SwitchHolder(private val b: ItemSettingSwitchBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SettingsItem) {
            b.tvLabel.text = item.label
            b.dragHandle.visibility = if (sortMode) View.VISIBLE else View.GONE
            b.root.alpha = 1f
            b.switchSetting.setOnCheckedChangeListener(null)
            b.switchSetting.isChecked = item.valueAsBoolean
            b.switchSetting.setOnCheckedChangeListener { _, checked ->
                item.boolValue = checked
                listener.onSwitchChanged(item, checked)
            }
        }
    }

    inner class MultiFrameHolder(private val b: ItemSettingMultiframeBinding) : RecyclerView.ViewHolder(b.root) {
        private var touchListener: com.google.android.material.slider.Slider.OnSliderTouchListener? = null
        private var changeListener: com.google.android.material.slider.Slider.OnChangeListener? = null

        fun bind(item: SettingsItem) {
            b.tvLabel.text = item.label
            b.dragHandle.visibility = if (sortMode) View.VISIBLE else View.GONE
            b.root.alpha = 1f

            val switch = b.switchSetting
            val frameCount = item.value.toInt().coerceIn(2, 8)
            val row = b.frameCountRow
            val slider = b.slider

            fun updateFrameLabel(n: Int) {
                b.tvFrameCount.text = "合成帧数: $n"
            }

            switch.setOnCheckedChangeListener(null)
            switch.isChecked = item.valueAsBoolean
            // 初始状态直接设置，不播放动画（避免列表刷新时闪烁）
            row.visibility = if (item.valueAsBoolean) View.VISIBLE else View.GONE
            switch.setOnCheckedChangeListener { _, checked ->
                item.boolValue = checked
                if (checked) expandFrameRow(row) else collapseFrameRow(row)
                listener.onSwitchChanged(item, checked)
            }

            // 先移除旧监听器，避免复用绑定设置 value 时触发旧监听器
            touchListener?.let { slider.removeOnSliderTouchListener(it) }
            changeListener?.let { slider.removeOnChangeListener(it) }
            touchListener = null
            changeListener = null

            slider.valueFrom = 0f
            slider.valueTo = 6f
            slider.stepSize = 1f
            slider.setTickVisible(false)
            slider.setLabelBehavior(2)
            slider.value = (frameCount - 2).coerceIn(0, 6).toFloat()
            updateFrameLabel(frameCount)

            touchListener = object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) { onSliderFocus(b.root) }
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    val n = slider.value.toInt() + 2
                    item.value = n.toFloat()
                    listener.onSliderCommit(item, n.toFloat())
                    onSliderBlur()
                }
            }
            changeListener = com.google.android.material.slider.Slider.OnChangeListener { _, value, _ ->
                val n = value.toInt() + 2
                item.value = n.toFloat()
                updateFrameLabel(n)
                listener.onSliderLive(item, n.toFloat())
            }
            slider.addOnSliderTouchListener(touchListener!!)
            slider.addOnChangeListener(changeListener!!)
        }

        /** 展开 frameCountRow：高度 0 → 完整高度，淡入。 */
        private fun expandFrameRow(row: View) {
            row.measure(
                View.MeasureSpec.makeMeasureSpec((row.parent as? View)?.width ?: 0, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = row.measuredHeight
            if (targetHeight <= 0) {
                row.visibility = View.VISIBLE
                row.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                return
            }
            row.visibility = View.VISIBLE
            row.layoutParams.height = 0
            row.alpha = 0f
            val anim = ValueAnimator.ofInt(0, targetHeight)
            anim.duration = 220L
            anim.interpolator = DecelerateInterpolator()
            anim.addUpdateListener { va ->
                row.layoutParams.height = va.animatedValue as Int
                row.alpha = (va.animatedValue as Int).toFloat() / targetHeight
                row.requestLayout()
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    row.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    row.alpha = 1f
                    row.requestLayout()
                }
            })
            anim.start()
        }

        /** 收起 frameCountRow：完整高度 → 0，淡出后隐藏。 */
        private fun collapseFrameRow(row: View) {
            val startHeight = row.height
            if (startHeight <= 0) {
                row.visibility = View.GONE
                return
            }
            val anim = ValueAnimator.ofInt(startHeight, 0)
            anim.duration = 180L
            anim.interpolator = DecelerateInterpolator()
            anim.addUpdateListener { va ->
                row.layoutParams.height = va.animatedValue as Int
                row.alpha = (va.animatedValue as Int).toFloat() / startHeight
                row.requestLayout()
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    row.visibility = View.GONE
                    row.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    row.alpha = 1f
                    row.requestLayout()
                }
            })
            anim.start()
        }
    }

    inner class SliderHolder(private val b: ItemSettingSliderBinding) : RecyclerView.ViewHolder(b.root) {
        private var touchListener: com.google.android.material.slider.Slider.OnSliderTouchListener? = null
        private var changeListener: com.google.android.material.slider.Slider.OnChangeListener? = null

        fun bind(item: SettingsItem) {
            b.tvLabel.text = item.label
            b.dragHandle.visibility = if (sortMode) View.VISIBLE else View.GONE
            b.root.alpha = 1f

            val slider = b.slider
            // 先移除旧监听器，避免复用绑定设置 value 时触发旧监听器、或动画残留
            touchListener?.let { slider.removeOnSliderTouchListener(it) }
            changeListener?.let { slider.removeOnChangeListener(it) }
            touchListener = null
            changeListener = null

            slider.valueFrom = item.sliderMin
            slider.valueTo = item.sliderMax
            slider.stepSize = item.sliderStep
            slider.setTickVisible(false)
            slider.setLabelBehavior(2)
            slider.value = item.currentSliderPosition()

            val updateLabel: (Float) -> Unit = { pos ->
                val value = item.liveValue(pos)
                b.tvLabel.text = "${item.label} ${item.formatValue(value)}".trim()
            }
            updateLabel(slider.value)

            touchListener = object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    onSliderFocus(b.root)
                }
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    val value = item.liveValue(slider.value)
                    item.value = value
                    listener.onSliderCommit(item, value)
                    onSliderBlur()
                }
            }
            changeListener = com.google.android.material.slider.Slider.OnChangeListener { _, value, _ ->
                val real = item.liveValue(value)
                item.value = real
                updateLabel(value)
                listener.onSliderLive(item, real)
            }
            slider.addOnSliderTouchListener(touchListener!!)
            slider.addOnChangeListener(changeListener!!)
        }
    }

    inner class ThemeHolder(private val b: ItemSettingThemeBinding) : RecyclerView.ViewHolder(b.root) {
        private var themeAdapter: ThemeSelectorAdapter? = null

        fun bind(item: SettingsItem) {
            b.tvLabel.text = item.label
            b.dragHandle.visibility = if (sortMode) View.VISIBLE else View.GONE
            b.root.alpha = 1f
            if (b.themeRecyclerView.layoutManager == null) {
                b.themeRecyclerView.layoutManager =
                    LinearLayoutManager(b.root.context, LinearLayoutManager.HORIZONTAL, false)
            }
            b.themeRecyclerView.isNestedScrollingEnabled = false
            if (themeAdapter == null) {
                themeAdapter = ThemeSelectorAdapter(themes, selectedTheme, onThemeClick)
                b.themeRecyclerView.adapter = themeAdapter
            }
        }
    }

    inner class CurveHolder(private val b: ItemSettingCurveBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SettingsItem) {
            b.tvLabel.text = item.label
            b.dragHandle.visibility = if (sortMode) View.VISIBLE else View.GONE
            b.root.alpha = 1f
            val editor = b.curveEditor
            editor.setCurve(item.curve ?: ToneCurve())
            editor.onDragStart = { onSliderFocus(b.root) }
            editor.onDragEnd = { onSliderBlur() }
            editor.onCurveChanged = { curve ->
                item.curve = curve
                listener.onCurveChanged(curve)
            }
        }
    }

    /** 拖拽滑块 / 曲线时：隐藏其它项，只保留当前项，弹窗全透明。 */
    private fun onSliderFocus(keep: View) {
        recycler?.let { rv ->
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                child.alpha = if (child === keep) 1f else 0f
            }
        }
        dialog?.window?.let { w ->
            w.setDimAmount(0f)
            w.decorView.background = null
        }
    }

    private fun onSliderBlur() {
        recycler?.let { rv ->
            for (i in 0 until rv.childCount) {
                rv.getChildAt(i).alpha = 1f
            }
        }
        dialog?.window?.let { w ->
            w.setDimAmount(0.6f)
            w.decorView.background = dialogBg
        }
    }
}
