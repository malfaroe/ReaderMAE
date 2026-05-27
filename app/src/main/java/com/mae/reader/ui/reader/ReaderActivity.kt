package com.mae.reader.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.mae.reader.databinding.ActivityReaderBinding
import com.mae.reader.epub.EpubBook
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val vm: ReaderViewModel by viewModels()

    private var bookCache: EpubBook? = null
    private var uiVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupTapZones()
        setupTocButton()
        observeState()

        val uri = intent.data ?: intent.getParcelableExtra(EXTRA_URI)
        if (uri != null) vm.loadBook(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = false
            settings.builtInZoomControls = false
            settings.setSupportZoom(false)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Restaurar scroll guardado solo en el primer capítulo al abrir
                }
            }
        }
    }

    private fun setupTapZones() {
        binding.webView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                val w = v.width.toFloat()
                when {
                    x < w * 0.25f -> { vm.previousChapter(); true }
                    x > w * 0.75f -> { vm.nextChapter(); true }
                    else -> {
                        toggleUi()
                        v.performClick()
                        true
                    }
                }
            } else false
        }
    }

    private fun setupTocButton() {
        binding.btnToc.setOnClickListener { showTocDialog() }
        binding.btnPrev.setOnClickListener { vm.previousChapter() }
        binding.btnNext.setOnClickListener { vm.nextChapter() }
        binding.overlayTop.setOnClickListener { toggleUi() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            vm.state.collectLatest { state ->
                when (state) {
                    is ReaderState.Loading -> binding.progress.isVisible = true
                    is ReaderState.Ready -> {
                        binding.progress.isVisible = false
                        bookCache = state.book
                        binding.tvTitle.text = state.book.title
                        collectChapterChanges()
                    }
                    is ReaderState.Error -> {
                        binding.progress.isVisible = false
                        binding.tvTitle.text = state.message
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun collectChapterChanges() {
        lifecycleScope.launch {
            vm.chapterIndex.collectLatest { idx ->
                val book = bookCache ?: return@collectLatest
                val chapter = book.chapters.getOrNull(idx) ?: return@collectLatest
                binding.tvChapterTitle.text = chapter.title
                binding.tvProgress.text = "${idx + 1} / ${book.chapters.size}"
                renderChapter(chapter.htmlContent)
            }
        }
    }

    private fun renderChapter(html: String) {
        val full = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    font-family: Georgia, 'Times New Roman', serif;
                    font-size: 19px;
                    line-height: 1.7;
                    color: #1a1a1a;
                    background-color: #FAF8F1;
                    margin: 0;
                    padding: 24px 20px 60px 20px;
                    word-break: break-word;
                }
                p { text-align: justify; margin: 0 0 1em 0; }
                h1, h2, h3 { font-family: Georgia, serif; line-height: 1.3; }
                img { max-width: 100%; height: auto; display: block; margin: 12px auto; }
                a { color: #1a1a1a; text-decoration: none; }
            </style></head><body>
            $html
            </body></html>
        """.trimIndent()
        binding.webView.loadDataWithBaseURL(null, full, "text/html", "UTF-8", null)
    }

    private fun toggleUi() {
        uiVisible = !uiVisible
        val vis = if (uiVisible) View.VISIBLE else View.GONE
        binding.overlayTop.visibility = vis
        binding.overlayBottom.visibility = vis
    }

    private fun showTocDialog() {
        val book = bookCache ?: return
        val titles = book.toc.map { it.title }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Contenido")
            .setItems(titles) { _, which ->
                vm.goToChapter(book.toc[which].chapterIndex)
                toggleUi()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.evaluateJavascript("window.scrollY") { value ->
            val scrollY = value?.toIntOrNull() ?: 0
            vm.savePosition(scrollY)
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"

        fun start(activity: AppCompatActivity, uri: Uri) {
            val intent = Intent(activity, ReaderActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
            }
            activity.startActivity(intent)
        }
    }
}
