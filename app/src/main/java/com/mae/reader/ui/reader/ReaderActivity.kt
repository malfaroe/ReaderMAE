package com.mae.reader.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.mae.reader.databinding.ActivityReaderBinding
import com.mae.reader.epub.EpubBook
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val vm: ReaderViewModel by viewModels()
    private lateinit var gestureDetector: GestureDetectorCompat

    private var bookCache: EpubBook? = null
    private var uiVisible = false

    // Toda la aritmética de página vive en píxeles físicos enteros.
    // page * pageHeightPx es siempre un entero → sin drift acumulativo.
    private var density = 1f
    private var lineHeightPx = 0       // px físicos por línea (entero exacto)
    private var lineHeightCss = 30.0   // px CSS por línea (= lineHeightPx / density)
    private var pageHeightPx = 0       // px físicos por página (múltiplo entero de lineHeightPx)
    private var pageHeightCss = 0.0    // px CSS por página (= pageHeightPx / density)

    private var totalPages = 1
    private var currentPage = 0
    private var restorePageOnLoad: Int? = null
    private var goToLastPageOnLoad = false

    companion object {
        const val TARGET_LINE_H = 30   // altura objetivo de línea en CSS px
        const val EXTRA_URI = "extra_uri"

        fun start(activity: AppCompatActivity, uri: Uri) {
            activity.startActivity(
                Intent(activity, ReaderActivity::class.java).putExtra(EXTRA_URI, uri)
            )
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        density = resources.displayMetrics.density

        showLoading("Abriendo libro…")
        setupWebView()
        setupGestures()
        setupButtons()
        observeState()

        val uri = intent.data ?: intent.getParcelableExtra<Uri>(EXTRA_URI)
        if (uri != null) vm.loadBook(uri)
    }

    override fun onPause() {
        super.onPause()
        vm.savePosition(currentPage)
    }

    // ── WebView ────────────────────────────────────────────────────────────

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
                    view.postDelayed({ measurePagesAndRestore(view) }, 250)
                }
            }
        }
        binding.webView.post { fitWebViewToLineGrid() }
    }

    /**
     * Calcula lineHeightPx como entero exacto de px físicos.
     * Redimensiona el WebView a un múltiplo entero de lineHeightPx.
     * Esto garantiza que page * pageHeightPx sea siempre entero → cero drift.
     */
    private fun fitWebViewToLineGrid() {
        val webViewPx = binding.webView.height
        if (webViewPx <= 0) { binding.webView.postDelayed(::fitWebViewToLineGrid, 50); return }

        // Entero físico más cercano a TARGET_LINE_H CSS px en esta pantalla
        lineHeightPx  = (TARGET_LINE_H * density).roundToInt()
        lineHeightCss = lineHeightPx.toDouble() / density

        // Máximo múltiplo entero de lineHeightPx que cabe en el WebView
        val linesPerPage = webViewPx / lineHeightPx
        pageHeightPx  = linesPerPage * lineHeightPx
        pageHeightCss = pageHeightPx.toDouble() / density

        if (binding.webView.height != pageHeightPx) {
            binding.webView.updateLayoutParams<ViewGroup.LayoutParams> { height = pageHeightPx }
        }
    }

    // ── Paginación ─────────────────────────────────────────────────────────

    private fun measurePagesAndRestore(view: WebView) {
        if (pageHeightPx <= 0) { fitWebViewToLineGrid(); return }

        // scrollHeight viene en CSS px → convertir a px físicos para división entera exacta
        view.evaluateJavascript(
            "document.documentElement.scrollHeight - 300"
        ) { result ->
            val contentCss = result?.trim()?.toDoubleOrNull()?.coerceAtLeast(lineHeightCss)
                ?: pageHeightCss
            val contentPx = (contentCss * density).roundToInt()
            totalPages = maxOf(1, contentPx / pageHeightPx)

            currentPage = when {
                restorePageOnLoad != null -> {
                    val p = restorePageOnLoad!!.coerceAtMost(totalPages - 1)
                    restorePageOnLoad = null
                    p
                }
                goToLastPageOnLoad -> { goToLastPageOnLoad = false; totalPages - 1 }
                else -> 0
            }
            if (currentPage > 0) scrollToPage(currentPage)
            updateProgress()
            hideLoading()
        }
    }

    private fun scrollToPage(page: Int) {
        // scrollCss * density = page * pageHeightCss * density = page * pageHeightPx (entero)
        // El WebView convierte de vuelta al entero físico exacto → sin drift acumulativo
        val scrollCss = page.toDouble() * pageHeightCss
        binding.webView.evaluateJavascript("window.scrollTo(0,$scrollCss);void 0;", null)
    }

    private fun updateProgress() {
        val book = bookCache ?: return
        val ch = vm.chapterIndex.value
        binding.tvProgress.text = "Cap ${ch + 1}/${book.chapters.size} · ${currentPage + 1}/$totalPages"
    }

    // ── Navegación ────────────────────────────────────────────────────────

    private fun navigateNext() {
        if (currentPage < totalPages - 1) {
            currentPage++; scrollToPage(currentPage); updateProgress()
        } else { vm.nextChapter() }
    }

    private fun navigatePrevious() {
        if (currentPage > 0) {
            currentPage--; scrollToPage(currentPage); updateProgress()
        } else { goToLastPageOnLoad = true; vm.previousChapter() }
    }

    // ── Gestos ────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val x = e.x; val w = binding.webView.width.toFloat()
                    when {
                        x < w * 0.3f -> navigatePrevious()
                        x > w * 0.7f -> navigateNext()
                        else         -> toggleUi()
                    }
                    return true
                }
            })
        binding.webView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); false }
    }

    // ── Botones ───────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnTocFixed.setOnClickListener { showTocDialog() }
        binding.btnToc.setOnClickListener     { showTocDialog() }
        binding.btnPrev.setOnClickListener    { navigatePrevious() }
        binding.btnNext.setOnClickListener    { navigateNext() }
        binding.overlayTop.setOnClickListener { toggleUi() }
    }

    // ── Observadores ──────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            vm.state.collectLatest { state ->
                when (state) {
                    is ReaderState.Loading -> showLoading()
                    is ReaderState.Ready   -> {
                        bookCache = state.book
                        binding.tvTitle.text = state.book.title
                        binding.tvLoadingTitle.text = state.book.title
                        restorePageOnLoad = vm.savedPageIndex
                        collectChapterChanges()
                    }
                    is ReaderState.Error   -> { hideLoading(); binding.tvTitle.text = state.message }
                    else -> Unit
                }
            }
        }
    }

    private fun collectChapterChanges() {
        lifecycleScope.launch {
            vm.chapterIndex.collectLatest { idx ->
                val book    = bookCache ?: return@collectLatest
                val chapter = book.chapters.getOrNull(idx) ?: return@collectLatest
                binding.tvChapterTitle.text = chapter.title
                showLoading(chapter.title.ifEmpty { book.title })
                binding.webView.scrollTo(0, 0)
                renderChapter(chapter.htmlContent)
            }
        }
    }

    // ── Renderizado ───────────────────────────────────────────────────────

    private fun renderChapter(html: String) {
        val lh = if (lineHeightCss > 0) lineHeightCss else TARGET_LINE_H.toDouble()
        val full = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1.0">
            <style>
              *,html,body{margin:0;padding:0;box-sizing:border-box;background:#000}
              body{
                font-family:Georgia,'Times New Roman',serif;
                font-size:18px;
                line-height:${lh}px;
                color:#E8E8E8;
                padding:0 22px 300px 22px;
                -webkit-text-size-adjust:none;
                overflow-x:hidden;
              }
              p {text-align:justify;margin:0 0 ${lh}px 0;}
              h1{color:#fff;font-size:24px;line-height:${lh}px;margin:${lh}px 0;}
              h2{color:#fff;font-size:20px;line-height:${lh}px;margin:${lh}px 0;}
              h3{color:#ddd;font-size:18px;line-height:${lh}px;margin:${lh}px 0;}
              img{max-width:100%;height:auto;display:block;margin:${lh}px auto;}
              a{color:#aaa;text-decoration:none;}
            </style>
            </head><body>$html</body></html>
        """.trimIndent()
        binding.webView.loadDataWithBaseURL("about:blank", full, "text/html", "UTF-8", null)
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private fun showLoading(title: String = "Abriendo libro…") {
        binding.loadingOverlay.isVisible = true
        binding.tvLoadingTitle.text = title
    }

    private fun hideLoading() { binding.loadingOverlay.isVisible = false }

    private fun toggleUi() {
        uiVisible = !uiVisible
        val vis = if (uiVisible) View.VISIBLE else View.GONE
        binding.overlayTop.visibility   = vis
        binding.overlayBottom.visibility = vis
    }

    private fun showTocDialog() {
        val book = bookCache ?: return
        val titles = book.toc.map { it.title }.toTypedArray()
        if (titles.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(titles) { _, which ->
                restorePageOnLoad  = null
                goToLastPageOnLoad = false
                vm.goToChapter(book.toc[which].chapterIndex)
                if (uiVisible) toggleUi()
            }.show()
    }
}
