package com.classic.camera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ThemeEntry(
    val id: String,
    val label: String,
    val colors: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThemeEntry) return false
        return id == other.id
    }

    override fun hashCode() = id.hashCode()
}

class ThemeSelectorAdapter(
    private val themes: List<ThemeEntry>,
    private val selectedId: String,
    private val onItemClick: (ThemeEntry) -> Unit
) : RecyclerView.Adapter<ThemeSelectorAdapter.ViewHolder>() {

    override fun getItemCount() = themes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_theme_selector, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = themes[position]
        val isSelected = entry.id == selectedId

        holder.name.text = entry.label
        holder.name.setTextColor(
            if (isSelected) holder.itemView.context.getAttrColor(R.attr.textPrimary)
            else holder.itemView.context.getAttrColor(R.attr.textSecondary)
        )

        holder.previewCard.strokeWidth = if (isSelected)
            holder.itemView.resources.getDimensionPixelSize(R.dimen.border_medium)
        else
            0
        holder.previewCard.strokeColor = if (isSelected)
            holder.itemView.context.getAttrColor(R.attr.accentColor)
        else
            0

        holder.grid.removeAllViews()
        val ctx = holder.itemView.context
        val chunk = entry.colors
        for (row in 0..1) {
            val rowLayout = LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                orientation = LinearLayout.HORIZONTAL
            }
            for (col in 0..1) {
                val idx = row * 2 + col
                val color = if (idx < chunk.size) chunk[idx] else 0
                val cell = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setBackgroundColor(color)
                }
                rowLayout.addView(cell)
            }
            holder.grid.addView(rowLayout)
        }

        holder.itemView.setOnClickListener {
            onItemClick(entry)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val previewCard: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.themePreviewCard)
        val grid: LinearLayout = view.findViewById(R.id.themeGrid)
        val name: TextView = view.findViewById(R.id.tvThemeName)
    }
}
