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
    private var uiVisible = true

    // Física de paginación en px físicos enteros — sin drift acumulativo
    private var density = 1f
    private var lineHeightPx = 0
    private var lineHeightCss = 30.0
    private var pageHeightPx = 0
    private var pageHeightCss = 0.0

    private var totalPages = 1
    private var currentPage = 0
    private var restorePageOnLoad: Int? = null
    private var goToLastPageOnLoad = false

    private var fontSize = 18
    private var currentChapterHtml = ""

    companion object {
        const val TARGET_LINE_H = 30
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

    private fun fitWebViewToLineGrid() {
        val webViewPx = binding.webView.height
        if (webViewPx <= 0) { binding.webView.postDelayed(::fitWebViewToLineGrid, 50); return }

        lineHeightPx  = (TARGET_LINE_H * density).roundToInt()
        lineHeightCss = lineHeightPx.toDouble() / density

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

        view.evaluateJavascript("document.documentElement.scrollHeight - 300") { result ->
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
        val scrollCss = page.toDouble() * pageHeightCss
        binding.webView.evaluateJavascript("window.scrollTo(0,$scrollCss);void 0;", null)
    }

    private fun updateProgress() {
        binding.tvProgress.text = "${currentPage + 1} / $totalPages"
    }

    // ── Tamaño de fuente (sin recargar la página) ──────────────────────────

    private fun changeFontSize(delta: Int) {
        val newSize = (fontSize + delta).coerceIn(14, 24)
        if (newSize == fontSize || currentChapterHtml.isEmpty()) return
        fontSize = newSize

        // Cambia tamaño directamente en el DOM — sin reload, sin overlay, sin salto
        val js = """
            (function(){
              var s=document.body.style;
              s.fontSize='${fontSize}px';
              document.querySelectorAll('h1').forEach(function(e){e.style.fontSize='${fontSize+6}px';});
              document.querySelectorAll('h2').forEach(function(e){e.style.fontSize='${fontSize+2}px';});
              document.querySelectorAll('h3').forEach(function(e){e.style.fontSize='${fontSize}px';});
            })();void 0;
        """.trimIndent()

        binding.webView.evaluateJavascript(js) {
            // Tras el reflow del DOM, recalcular páginas y volver a la posición actual
            binding.webView.postDelayed({ silentRemeasure() }, 200)
        }
    }

    private fun silentRemeasure() {
        if (pageHeightPx <= 0) return
        binding.webView.evaluateJavascript("document.documentElement.scrollHeight - 300") { result ->
            val contentCss = result?.trim()?.toDoubleOrNull()?.coerceAtLeast(lineHeightCss)
                ?: pageHeightCss
            val contentPx = (contentCss * density).roundToInt()
            totalPages = maxOf(1, contentPx / pageHeightPx)
            currentPage = currentPage.coerceAtMost(totalPages - 1)
            scrollToPage(currentPage)
            updateProgress()
        }
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
        binding.btnToc.setOnClickListener      { showTocDialog() }
        binding.btnPrev.setOnClickListener     { navigatePrevious() }
        binding.btnNext.setOnClickListener     { navigateNext() }
        binding.btnFontDown.setOnClickListener { changeFontSize(-2) }
        binding.btnFontUp.setOnClickListener   { changeFontSize(+2) }
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
                val title   = chapter.title.ifEmpty { book.title }
                binding.tvTitle.text = title
                showLoading(title)
                binding.webView.scrollTo(0, 0)
                currentChapterHtml = chapter.htmlContent
                renderChapter(currentChapterHtml)
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
                font-size:${fontSize}px;
                line-height:${lh}px;
                color:#E8E8E8;
                padding:0 22px 300px 22px;
                -webkit-text-size-adjust:none;
                overflow-x:hidden;
              }
              p {text-align:justify;margin:0 0 ${lh}px 0;}
              h1{color:#fff;font-size:${fontSize + 6}px;line-height:${lh}px;margin:${lh}px 0;}
              h2{color:#fff;font-size:${fontSize + 2}px;line-height:${lh}px;margin:${lh}px 0;}
              h3{color:#ddd;font-size:${fontSize}px;line-height:${lh}px;margin:${lh}px 0;}
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

    // Tap central: oculta/muestra barras sin redimensionar el WebView
    private fun toggleUi() {
        uiVisible = !uiVisible
        val vis = if (uiVisible) View.VISIBLE else View.INVISIBLE
        binding.overlayTop.visibility    = vis
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
            }.show()
    }
}
