package com.mae.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
            b.tvTitle.text    = item.bookTitle.ifEmpty { "Sin título" }
            b.tvAuthor.text   = item.bookAuthor.ifEmpty { "Autor desconocido" }
            b.tvProgress.text = "Cap. ${item.chapterIndex + 1}  ·  Pág. ${item.pageIndex + 1}"

            val bmp = item.coverPath?.let { loadCover(it) }
            if (bmp != null) {
                b.ivCover.setImageBitmap(bmp)
            } else {
                b.ivCover.setImageDrawable(null)   // muestra solo el fondo #1E1E1E
            }

            b.root.setOnClickListener      { onItemClick(item) }
            b.root.setOnLongClickListener  { onItemDelete(item); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ReadingPosition>() {
            override fun areItemsTheSame(a: ReadingPosition, b: ReadingPosition) =
                a.bookPath == b.bookPath
            override fun areContentsTheSame(a: ReadingPosition, b: ReadingPosition) = a == b
        }

        // Decodifica la imagen reducida al tamaño mínimo necesario (52dp ≈ 156px @ 3x)
        private fun loadCover(path: String): Bitmap? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0) return null

            val target = 200   // px suficientes para cubrir 52dp a cualquier densidad
            var sample = 1
            var w = opts.outWidth
            while (w > target * 2) { sample *= 2; w /= 2 }

            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
