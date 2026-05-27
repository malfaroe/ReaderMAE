package com.mae.reader.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.mae.reader.databinding.ActivityReaderBinding
import com.mae.reader.epub.EpubBook
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val vm: ReaderViewModel by viewModels()
    private lateinit var gestureDetector: GestureDetectorCompat

    private var bookCache: EpubBook? = null
    private var uiVisible = false

    // Estado de paginación
    private var currentPage = 0
    private var totalPages = 1
    private var pageHeightPx = 0        // calculado una vez por capítulo, guardado en Kotlin
    private var restorePageOnLoad: Int? = null
    private var goToLastPageOnLoad = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupGestures()
        setupButtons()
        observeState()

        val uri = intent.data ?: intent.getParcelableExtra<Uri>(EXTRA_URI)
        if (uri != null) vm.loadBook(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = false
            settings.setSupportZoom(false)
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(0xFF000000.toInt())
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Esperar al layout antes de calcular scrollHeight
                    view.postDelayed({ calculatePagesAndRestore(view) }, 200)
                }
            }
        }
    }

    private fun calculatePagesAndRestore(view: WebView) {
        // paddingTop = innerHeight % 30  →  la última línea termina EXACTAMENTE
        // en el borde de página y pageH = innerHeight → cero solapamiento.
        view.evaluateJavascript("""
            (function(){
                var lh     = 30;
                var innerH = window.innerHeight;
                var padTop = innerH % lh;
                document.body.style.paddingTop = padTop + 'px';
                void document.body.offsetHeight;          // fuerza reflow síncrono
                var contentH = Math.max(lh,
                    document.documentElement.scrollHeight - padTop - 300);
                return Math.ceil(contentH / innerH) + '|' + innerH;
            })()
        """.trimIndent()) { result ->
            val clean = result?.trim()?.removeSurrounding("\"") ?: "1|0"
            val parts = clean.split("|")
            totalPages   = parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            pageHeightPx = parts.getOrNull(1)?.toIntOrNull()
                               ?.takeIf { it > 0 } ?: binding.webView.height
            currentPage = when {
                restorePageOnLoad != null -> {
                    val p = restorePageOnLoad!!.coerceAtMost(totalPages - 1)
                    restorePageOnLoad = null
                    p
                }
                goToLastPageOnLoad -> {
                    goToLastPageOnLoad = false
                    totalPages - 1
                }
                else -> 0
            }
            if (currentPage > 0) scrollToPage(currentPage)
            updateProgress()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val x = e.x
                    val w = binding.webView.width.toFloat()
                    when {
                        x < w * 0.3f -> navigatePrevious()
                        x > w * 0.7f -> navigateNext()
                        else -> toggleUi()
                    }
                    return true
                }
            })

        // Pasa al GestureDetector pero deja que el WebView maneje el scroll (return false)
        binding.webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun setupButtons() {
        binding.btnTocFixed.setOnClickListener { showTocDialog() }
        binding.btnToc.setOnClickListener { showTocDialog() }
        binding.btnPrev.setOnClickListener { navigatePrevious() }
        binding.btnNext.setOnClickListener { navigateNext() }
        binding.overlayTop.setOnClickListener { toggleUi() }
    }

    // ── Navegación por páginas ──────────────────────────────────────────────

    private fun navigateNext() {
        if (currentPage < totalPages - 1) {
            currentPage++
            scrollToPage(currentPage)
            updateProgress()
        } else {
            vm.nextChapter()
        }
    }

    private fun navigatePrevious() {
        if (currentPage > 0) {
            currentPage--
            scrollToPage(currentPage)
            updateProgress()
        } else {
            goToLastPageOnLoad = true
            vm.previousChapter()
        }
    }

    private fun scrollToPage(page: Int) {
        val y = page * pageHeightPx
        binding.webView.evaluateJavascript("window.scrollTo(0, $y); void 0;", null)
    }

    private fun updateProgress() {
        val book = bookCache ?: return
        val chIdx = vm.chapterIndex.value
        binding.tvProgress.text =
            "Cap ${chIdx + 1}/${book.chapters.size}  ·  ${currentPage + 1}/$totalPages"
    }

    // ── Observadores ────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            vm.state.collectLatest { state ->
                when (state) {
                    is ReaderState.Loading -> binding.progress.isVisible = true
                    is ReaderState.Ready -> {
                        binding.progress.isVisible = false
                        bookCache = state.book
                        binding.tvTitle.text = state.book.title
                        restorePageOnLoad = vm.savedPageIndex
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
                binding.webView.scrollTo(0, 0)
                renderChapter(chapter.htmlContent)
            }
        }
    }

    private fun renderChapter(html: String) {
        // line-height fijo en 30px (entero) para que los límites de página
        // sean siempre múltiplos exactos de la altura de una línea → sin cortes.
        // padding-top: 0 para que la primera línea empiece en y=0 (en la cuadrícula).
        // Todos los márgenes de párrafo/heading son múltiplos de 30px.
        val full = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                * { box-sizing: border-box; }
                html, body { margin: 0; padding: 0; background: #000; }
                body {
                    font-family: Georgia, 'Times New Roman', serif;
                    font-size: 18px;
                    line-height: 30px;
                    color: #E8E8E8;
                    padding: 0 20px 300px 20px;
                    -webkit-text-size-adjust: none;
                    overflow-x: hidden;
                }
                p  { text-align: justify; margin: 0 0 30px 0; }
                h1 { color: #FFF; font-size: 24px; line-height: 30px; margin: 30px 0; }
                h2 { color: #FFF; font-size: 20px; line-height: 30px; margin: 30px 0; }
                h3 { color: #DDD; font-size: 18px; line-height: 30px; margin: 30px 0; }
                img { max-width: 100%; height: auto; display: block; margin: 30px auto; }
                a  { color: #AAAAAA; text-decoration: none; }
            </style>
            </head><body>
            $html
            </body></html>
        """.trimIndent()
        binding.webView.loadDataWithBaseURL("about:blank", full, "text/html", "UTF-8", null)
    }

    // ── UI overlay ───────────────────────────────────────────────────────────

    private fun toggleUi() {
        uiVisible = !uiVisible
        val vis = if (uiVisible) View.VISIBLE else View.GONE
        binding.overlayTop.visibility = vis
        binding.overlayBottom.visibility = vis
    }

    private fun showTocDialog() {
        val book = bookCache ?: return
        val titles = book.toc.map { it.title }.toTypedArray()
        if (titles.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(titles) { _, which ->
                restorePageOnLoad = null
                goToLastPageOnLoad = false
                vm.goToChapter(book.toc[which].chapterIndex)
                if (uiVisible) toggleUi()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        vm.savePosition(currentPage)
    }

    companion object {
        const val EXTRA_URI = "extra_uri"

        fun start(activity: AppCompatActivity, uri: Uri) {
            activity.startActivity(
                Intent(activity, ReaderActivity::class.java).putExtra(EXTRA_URI, uri)
            )
        }
    }
}
