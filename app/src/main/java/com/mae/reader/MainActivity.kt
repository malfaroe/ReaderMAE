package com.mae.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mae.reader.data.db.AppDatabase
import com.mae.reader.data.model.ReadingPosition
import com.mae.reader.databinding.ActivityMainBinding
import com.mae.reader.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: BookAdapter
    private val dao by lazy { AppDatabase.get(this).positionDao() }

    private val openEpub = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ReaderActivity.start(this, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            ReaderActivity.start(this, intent.data!!)
            return
        }

        setupRecyclerView()
        binding.btnOpenBook.setOnClickListener { pickEpubFile() }
    }

    override fun onResume() {
        super.onResume()
        loadLibrary()
    }

    private fun setupRecyclerView() {
        adapter = BookAdapter(
            onItemClick = { openBook(it) },
            onItemDelete = { deleteBook(it) }
        )
        binding.recyclerBooks.layoutManager = LinearLayoutManager(this)
        binding.recyclerBooks.adapter = adapter
    }

    private fun loadLibrary() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) { dao.getAll() }
            adapter.submitList(books)
            binding.tvEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openBook(position: ReadingPosition) {
        ReaderActivity.start(this, Uri.parse(position.bookPath))
    }

    private fun deleteBook(position: ReadingPosition) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("¿Eliminar \"${position.bookTitle}\" de la biblioteca?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.delete(position.bookPath) }
                    loadLibrary()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickEpubFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        openEpub.launch(intent)
    }
}
