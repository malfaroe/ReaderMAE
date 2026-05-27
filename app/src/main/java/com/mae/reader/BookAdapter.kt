package com.mae.reader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mae.reader.data.model.ReadingPosition
import com.mae.reader.databinding.ItemBookBinding

class BookAdapter(
    private val onItemClick: (ReadingPosition) -> Unit,
    private val onItemDelete: (ReadingPosition) -> Unit
) : ListAdapter<ReadingPosition, BookAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(private val b: ItemBookBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: ReadingPosition) {
            b.tvTitle.text = item.bookTitle.ifEmpty { "Sin título" }
            b.tvAuthor.text = item.bookAuthor.ifEmpty { "Autor desconocido" }
            b.tvProgress.text = "Cap. ${item.chapterIndex + 1}  ·  Pág. ${item.pageIndex + 1}"
            b.root.setOnClickListener { onItemClick(item) }
            b.root.setOnLongClickListener { onItemDelete(item); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ReadingPosition>() {
            override fun areItemsTheSame(a: ReadingPosition, b: ReadingPosition) =
                a.bookPath == b.bookPath
            override fun areContentsTheSame(a: ReadingPosition, b: ReadingPosition) = a == b
        }
    }
}
