package org.levimc.launcher.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.levimc.launcher.R
import org.levimc.launcher.core.playfab.SearchResult
import org.levimc.launcher.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private val items = mutableListOf<SearchResult>()
    private val selectedIds = mutableSetOf<String>()
    private val itemCache = mutableMapOf<String, SearchResult>()

    fun setItems(newItems: List<SearchResult>) {
        val oldSnapshot = items.toList()
        val diff = DiffUtil.calculateDiff(SearchDiffCallback(oldSnapshot, newItems))

        items.clear()
        items.addAll(newItems)
        newItems.forEach { itemCache[it.dlcId] = it }

        diff.dispatchUpdatesTo(this)
        onSelectionChanged(selectedIds.size)
    }

    fun appendItems(newItems: List<SearchResult>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        newItems.forEach { itemCache[it.dlcId] = it }
        notifyItemRangeInserted(start, newItems.size)
        onSelectionChanged(selectedIds.size)
    }

    fun getSelectedItems(): List<SearchResult> {
        return selectedIds.mapNotNull { itemCache[it] }
    }

    fun hasSelection(): Boolean = selectedIds.isNotEmpty()

    private fun toggleSelection(item: SearchResult) {
        if (!item.isOwned) return

        if (!selectedIds.add(item.dlcId)) {
            selectedIds.remove(item.dlcId)
        }

        val pos = items.indexOfFirst { it.dlcId == item.dlcId }
        if (pos >= 0) notifyItemChanged(pos)

        onSelectionChanged(selectedIds.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val b: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: SearchResult) {
            val selected = selectedIds.contains(item.dlcId)
            val card = b.root

            b.titleText.text = item.title
            b.creatorText.text = item.creator
            b.packTypeChip.text = item.packType.name

            b.ratingText.visibility = if (item.avgStars != null) View.VISIBLE else View.GONE
            if (item.avgStars != null) {
                b.ratingText.text = "%.1f ★".format(item.avgStars)
            }

            Glide.with(b.thumbnail)
                .load(item.imageUrl)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .into(b.thumbnail)

            b.badgeIcon.setImageResource(
                if (item.isOwned) R.drawable.ic_check else R.drawable.ic_lock
            )

            card.strokeWidth = if (selected) 3 else 0
            card.strokeColor = ContextCompat.getColor(card.context, R.color.primary)
            card.isClickable = item.isOwned
            card.isFocusable = item.isOwned

            b.notOwnedOverlay.visibility = if (item.isOwned) View.GONE else View.VISIBLE

            b.root.setOnClickListener {
                toggleSelection(item)
            }
        }
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private class SearchDiffCallback(
        private val old: List<SearchResult>,
        private val new: List<SearchResult>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].dlcId == new[n].dlcId
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }
}