package com.mae.reader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mae.reader.data.db.AppDatabase
import com.mae.reader.data.model.ReadingPosition
import com.mae.reader.epub.EpubBook
import com.mae.reader.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class ReaderState {
    object Idle : ReaderState()
    object Loading : ReaderState()
    data class Ready(val book: EpubBook) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).positionDao()
    private val parser = EpubParser(app)

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Idle)
    val state: StateFlow<ReaderState> = _state

    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex

    var savedPageIndex: Int = 0
        private set

    private var bookPath = ""
    private var bookTitle = ""
    private var bookAuthor = ""

    fun loadBook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = ReaderState.Loading
            runCatching { parser.parse(uri) }
                .onSuccess { book ->
                    bookPath = uri.toString()
                    bookTitle = book.title
                    bookAuthor = book.author
                    val saved = dao.get(bookPath)
                    _chapterIndex.value = saved?.chapterIndex ?: 0
                    savedPageIndex = saved?.pageIndex ?: 0
                    _state.value = ReaderState.Ready(book)
                }
                .onFailure {
                    _state.value = ReaderState.Error(it.message ?: "Error al abrir el libro")
                }
        }
    }

    fun nextChapter() {
        val book = (state.value as? ReaderState.Ready)?.book ?: return
        if (_chapterIndex.value < book.chapters.lastIndex) _chapterIndex.value++
    }

    fun previousChapter() {
        if (_chapterIndex.value > 0) _chapterIndex.value--
    }

    fun goToChapter(index: Int) {
        _chapterIndex.value = index
    }

    fun savePosition(pageIndex: Int) {
        if (bookPath.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.save(
                ReadingPosition(
                    bookPath = bookPath,
                    bookTitle = bookTitle,
                    bookAuthor = bookAuthor,
                    chapterIndex = _chapterIndex.value,
                    pageIndex = pageIndex
                )
            )
        }
    }
}
