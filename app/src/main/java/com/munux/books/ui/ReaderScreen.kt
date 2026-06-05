package com.munux.books.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munux.books.data.AppDatabase
import com.munux.books.data.Book
import com.munux.books.data.BookFormat
import com.munux.books.data.ReaderPrefs
import com.munux.books.data.ReaderPrefsRepo
import com.munux.books.data.ReaderTheme
import com.munux.books.data.TranslationDao
import com.munux.books.data.TranslationStatus
import com.munux.books.reader.EpubParser
import com.munux.books.reader.EpubResourceProvider
import com.munux.books.translation.TranslationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

enum class ReaderMode { Original, Bilingual, Translated }

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).bookDao()
    private val translationDao: TranslationDao = AppDatabase.get(app).translationDao()
    private val translation = TranslationManager.get(app)
    private val prefsRepo = ReaderPrefsRepo(app)

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state = _state.asStateFlow()

    private val _mode = MutableStateFlow(ReaderMode.Original)
    val mode = _mode.asStateFlow()

    val prefs = prefsRepo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPrefs()
    )

    private var resourceProvider: EpubResourceProvider? = null
    private var bookId: Long = -1

    val translationJob = translation.jobs
        .map { it[bookId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun load(id: Long) {
        if (bookId == id) return
        bookId = id
        viewModelScope.launch {
            val book = dao.getById(id) ?: run {
                _state.value = ReaderState.Error("Livro não encontrado")
                return@launch
            }
            viewModelScope.launch {
                dao.observeById(id).collect { updated ->
                    val s = _state.value
                    if (updated == null) return@collect
                    when (s) {
                        is ReaderState.Epub -> _state.value = s.copy(book = updated)
                        is ReaderState.Pdf -> _state.value = s.copy(book = updated)
                        else -> Unit
                    }
                }
            }
            viewModelScope.launch {
                translation.jobs
                    .map { it[id]?.done ?: -1 }
                    .distinctUntilChanged()
                    .collect { refreshTranslations() }
            }
            when (book.format) {
                BookFormat.EPUB -> loadEpub(book)
                BookFormat.PDF -> _state.value = ReaderState.Pdf(book, emptyMap())
                BookFormat.UNKNOWN -> _state.value = ReaderState.Error("Formato não suportado")
            }
            refreshTranslations()
            _mode.value = when {
                book.translationStatus == TranslationStatus.DONE -> ReaderMode.Translated
                book.translatedChapters > 0 -> ReaderMode.Bilingual
                else -> ReaderMode.Original
            }
        }
    }

    private suspend fun loadEpub(book: Book) {
        runCatching {
            val file = File(book.filePath)
            val parsed = withContext(Dispatchers.IO) { EpubParser.read(file) }
            if (book.totalPages != parsed.chapters.size) {
                dao.updateTotalPages(book.id, parsed.chapters.size)
            }
            resourceProvider?.close()
            resourceProvider = withContext(Dispatchers.IO) { EpubResourceProvider(file) }
            _state.value = ReaderState.Epub(
                book.copy(totalPages = parsed.chapters.size),
                parsed.chapters,
                emptyMap()
            )
        }.onFailure {
            _state.value = ReaderState.Error("Erro ao ler EPUB: ${it.message}")
        }
    }

    fun readResource(path: String): ByteArray? = resourceProvider?.read(path)

    override fun onCleared() {
        super.onCleared()
        resourceProvider?.close()
        resourceProvider = null
    }

    fun savePosition(chapter: Int, ratio: Float) {
        viewModelScope.launch {
            dao.updatePosition(bookId, chapter, ratio.coerceIn(0f, 1f))
        }
    }

    /** PDF ainda usa página inteira como unidade — converte pra ratio 0. */
    fun savePdfPage(page: Int) {
        viewModelScope.launch { dao.updatePosition(bookId, page, 0f) }
    }

    fun savePdfTotal(total: Int) {
        viewModelScope.launch { dao.updateTotalPages(bookId, total) }
    }

    fun setMode(newMode: ReaderMode) {
        _mode.value = newMode
        viewModelScope.launch { refreshTranslations() }
    }

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        viewModelScope.launch { prefsRepo.update(transform) }
    }

    fun applyPreset(preset: ReaderPrefs) = updatePrefs { preset }

    fun startTranslation() {
        translation.translateBook(bookId)
        viewModelScope.launch { refreshTranslations() }
    }

    fun retranslate() {
        viewModelScope.launch {
            translation.cancel(bookId)
            translationDao.deleteForBook(bookId)
            dao.updateTranslationStatus(bookId, TranslationStatus.NONE, 0, null, null)
            refreshTranslations()
            translation.translateBook(bookId)
        }
    }

    fun cancelTranslation() {
        translation.cancel(bookId)
    }

    private suspend fun refreshTranslations() {
        val book = dao.getById(bookId) ?: return
        val lang = book.translationLang ?: return
        val entries = translationDao.getAllForBook(bookId, lang)
        val map = entries.associate { it.chapterIndex to it.translatedContent }
        when (val s = _state.value) {
            is ReaderState.Epub -> _state.value = s.copy(translatedHtml = map)
            is ReaderState.Pdf -> _state.value = s.copy(translatedText = map)
            else -> Unit
        }
    }
}

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    data class Epub(
        val book: Book,
        val chapters: List<EpubParser.Chapter>,
        val translatedHtml: Map<Int, String>
    ) : ReaderState
    data class Pdf(
        val book: Book,
        val translatedText: Map<Int, String>
    ) : ReaderState
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel()
    LaunchedEffect(bookId) { vm.load(bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val job by vm.translationJob.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    val currentBook = when (val s = state) {
        is ReaderState.Epub -> s.book
        is ReaderState.Pdf -> s.book
        else -> null
    }
    val translationRunning = job?.running == true
    val hasAnyTranslation = currentBook != null && (
        currentBook.translatedChapters > 0 ||
            currentBook.translationStatus == TranslationStatus.DONE ||
            currentBook.translationStatus == TranslationStatus.IN_PROGRESS
        )

    var showAppearance by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentBook?.title.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    if (currentBook != null) {
                        // Botão Aa — aparência (tema, fonte, presets)
                        IconButton(onClick = { showAppearance = true }) {
                            Icon(Icons.Default.FormatSize, contentDescription = "Aparência")
                        }
                        if (hasAnyTranslation) {
                            ReaderModeMenu(current = mode, onSelect = vm::setMode)
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Opções de tradução")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                when {
                                    translationRunning -> DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                        text = { Text("Cancelar tradução") },
                                        onClick = { showMenu = false; vm.cancelTranslation() }
                                    )
                                    !hasAnyTranslation -> DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                        text = { Text("Traduzir livro") },
                                        onClick = { showMenu = false; vm.startTranslation() }
                                    )
                                    currentBook.translationStatus != TranslationStatus.DONE -> {
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                            text = { Text("Continuar tradução") },
                                            onClick = { showMenu = false; vm.startTranslation() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Retraduzir do zero") },
                                            onClick = { showMenu = false; vm.retranslate() }
                                        )
                                    }
                                    else -> DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                        text = { Text("Retraduzir do zero") },
                                        onClick = { showMenu = false; vm.retranslate() }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ReaderState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReaderState.Error -> Text(s.message, Modifier.align(Alignment.Center))
                is ReaderState.Epub -> EpubReaderView(
                    chapters = s.chapters,
                    translated = s.translatedHtml,
                    mode = mode,
                    prefs = prefs,
                    readResource = vm::readResource,
                    startChapter = s.book.currentPage.coerceIn(0, (s.chapters.size - 1).coerceAtLeast(0)),
                    startRatio = s.book.currentScrollRatio,
                    onPositionChanged = vm::savePosition
                )
                is ReaderState.Pdf -> PdfReaderView(
                    book = s.book,
                    translatedText = s.translatedText,
                    mode = mode,
                    onPageChanged = vm::savePdfPage,
                    onTotalPagesResolved = vm::savePdfTotal
                )
            }

            // Filtro warm — overlay simples sobre tudo, sem mexer no rendering interno.
            if (prefs.warmFilter) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFFB347).copy(alpha = 0.08f))
                )
            }

            if (translationRunning) {
                val total = job?.total ?: currentBook?.totalPages?.coerceAtLeast(1) ?: 1
                val done = job?.done ?: currentBook?.translatedChapters ?: 0
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                ) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val eta = job?.etaMs?.takeIf { it > 0 }?.let { " • ~${formatRemaining(it)} restantes" } ?: ""
                    Text(
                        "Traduzindo $done/$total$eta",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                    job?.note?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp)
                        )
                    }
                }
            } else if (currentBook != null && currentBook.translationStatus == TranslationStatus.FAILED) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    currentBook.retryAfterAt?.let { RetryCountdown(it) }
                        ?: currentBook.translationError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                }
            }
        }
    }

    if (showAppearance) {
        AppearanceSheet(
            prefs = prefs,
            onChange = { vm.updatePrefs { _ -> it } },
            onPreset = vm::applyPreset,
            onDismiss = { showAppearance = false }
        )
    }
}

/**
 * Bottom sheet de Aparência — Tema, Fonte, Margens, Filtro warm + presets Manhã/Noite.
 * Decisões e referências em docs/leitor-decisoes.md §3, §6, §7, §9.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSheet(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
    onPreset: (ReaderPrefs) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Aparência", style = MaterialTheme.typography.titleLarge)

            // Presets Manhã/Noite
            Text("Presets", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onPreset(ReaderPrefsRepo.PRESET_MORNING) },
                    label = { Text("🌅 Manhã") }
                )
                AssistChip(
                    onClick = { onPreset(ReaderPrefsRepo.PRESET_NIGHT) },
                    label = { Text("🌙 Noite") }
                )
            }

            // Tema
            Text("Tema", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.values().forEach { theme ->
                    FilterChip(
                        selected = prefs.theme == theme,
                        onClick = { onChange(prefs.copy(theme = theme)) },
                        label = { Text(themeLabel(theme)) }
                    )
                }
            }

            // Tamanho da fonte
            Text(
                "Tamanho da fonte: ${prefs.fontSizeSp}sp",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = prefs.fontSizeSp.toFloat(),
                onValueChange = { onChange(prefs.copy(fontSizeSp = it.toInt())) },
                valueRange = 13f..28f,
                steps = 14
            )

            // Altura de linha
            Text(
                "Altura de linha: ${String.format("%.2f", prefs.lineHeight)}",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = prefs.lineHeight,
                onValueChange = { onChange(prefs.copy(lineHeight = it)) },
                valueRange = 1.2f..2.0f,
                steps = 7
            )

            // Margens laterais
            Text(
                "Margem lateral: ${prefs.sideMarginDp}dp",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = prefs.sideMarginDp.toFloat(),
                onValueChange = { onChange(prefs.copy(sideMarginDp = it.toInt())) },
                valueRange = 8f..40f,
                steps = 7
            )

            // Filtro warm
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Filtro warm noturno", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Aplica um tom levemente amarelado sobre qualquer tema. " +
                            "Evidência sobre melhorar sono é fraca — use só se for confortável visualmente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = prefs.warmFilter,
                    onCheckedChange = { onChange(prefs.copy(warmFilter = it)) }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "💡 Em ônibus/carro: olhe pela janela por 20–30s a cada 5 min de leitura. " +
                    "Re-sincroniza o sistema vestibular e reduz enjoo (Reason & Brand, 1975).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun themeLabel(t: ReaderTheme): String = when (t) {
    ReaderTheme.LIGHT -> "Claro"
    ReaderTheme.SEPIA -> "Sépia"
    ReaderTheme.DARK -> "Escuro"
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun EpubReaderView(
    chapters: List<EpubParser.Chapter>,
    translated: Map<Int, String>,
    mode: ReaderMode,
    prefs: ReaderPrefs,
    readResource: (String) -> ByteArray?,
    startChapter: Int,
    startRatio: Float,
    onPositionChanged: (chapter: Int, ratio: Float) -> Unit
) {
    if (chapters.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("EPUB sem conteúdo legível")
        }
        return
    }
    val pager = rememberPagerState(initialPage = startChapter, pageCount = { chapters.size })
    val scope = rememberCoroutineScope()

    // Página dentro do capítulo atual (reportada pelo JS do WebView)
    var pageInChapter by remember { mutableIntStateOf(0) }
    var pagesInChapter by remember { mutableIntStateOf(1) }

    // Ratio inicial para o capítulo atual — só usa o salvo no DB para o capítulo inicial,
    // os outros começam em 0.
    val initialRatioForChapter = remember(pager.currentPage) {
        if (pager.currentPage == startChapter) startRatio else 0f
    }

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }
            .distinctUntilChanged()
            .debounce(150)
            .collect { /* current chapter changes; position salva via callback do JS */ }
    }

    val client = remember(readResource) {
        object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (url.host != EPUB_BASE_HOST) return null
                val path = url.path?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return null
                val bytes = readResource(path) ?: return null
                return WebResourceResponse(
                    EpubResourceProvider.mimeTypeFor(path),
                    null,
                    ByteArrayInputStream(bytes)
                )
            }
        }
    }

    PagedReader(pager, pageInChapter, pagesInChapter) { index ->
        val chapter = chapters[index]
        val translatedHtml = translated[index]
        val initialRatio = if (index == startChapter) initialRatioForChapter else 0f
        val finalHtml = remember(index, mode, translatedHtml, prefs, initialRatio) {
            val body = when (mode) {
                ReaderMode.Original -> chapter.html
                ReaderMode.Translated -> translatedHtml ?: chapter.html
                ReaderMode.Bilingual -> buildBilingualBody(chapter.html, translatedHtml)
            }
            wrapHtml(body, prefs).replace("__MUNUX_INITIAL_RATIO__", initialRatio.toString())
        }
        val baseUrl = remember(chapter.href) { baseUrlForChapter(chapter.href) }

        // Tag chave usada pra evitar recarregar a WebView a cada recomposição
        // (toda vez que o JS dispara onPage o estado muda e o `update` é refeito).
        // Recarregar em loop deixa a tela em branco — só recarregamos se o conteúdo
        // realmente mudou.
        val contentKey = remember(finalHtml, baseUrl) { "$baseUrl|${finalHtml.hashCode()}" }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = client
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            Log.d(
                                "MunuxJS",
                                "[${msg.messageLevel()}] ${msg.message()} (line ${msg.lineNumber()})"
                            )
                            return true
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.defaultTextEncodingName = "UTF-8"
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER

                    val bridge = ChapterBridge(
                        chapterIndex = index,
                        onPage = { page, total, ratio ->
                            post {
                                if (index == pager.currentPage) {
                                    pageInChapter = page
                                    pagesInChapter = total
                                }
                                onPositionChanged(index, ratio)
                            }
                        },
                        onCrossChapter = { dir ->
                            post {
                                val target = (pager.currentPage + dir).coerceIn(0, chapters.size - 1)
                                if (target != pager.currentPage) {
                                    scope.launch { pager.animateScrollToPage(target) }
                                }
                            }
                        }
                    )
                    addJavascriptInterface(bridge, "MunuxBridge")

                    // Carga inicial — garante que o conteúdo aparece mesmo que o
                    // `update` não dispare uma segunda vez por algum motivo.
                    setTag(contentKey)
                    loadDataWithBaseURL(baseUrl, finalHtml, "text/html", "UTF-8", baseUrl)
                }
            },
            update = { web ->
                web.webViewClient = client
                val loaded = web.getTag() as? String
                if (loaded != contentKey) {
                    web.setTag(contentKey)
                    web.loadDataWithBaseURL(baseUrl, finalHtml, "text/html", "UTF-8", baseUrl)
                }
            }
        )
    }
}

/**
 * Ponte JS↔Kotlin para o WebView de cada capítulo. Documentação em
 * docs/leitor-decisoes.md §1.
 */
private class ChapterBridge(
    private val chapterIndex: Int,
    private val onPage: (page: Int, total: Int, ratio: Float) -> Unit,
    private val onCrossChapter: (direction: Int) -> Unit
) {
    @JavascriptInterface
    fun onPage(page: Int, total: Int, ratio: Float) {
        onPage.invoke(page, total, ratio.coerceIn(0f, 1f))
    }

    @JavascriptInterface
    fun onCrossChapter(direction: Int) {
        onCrossChapter.invoke(direction)
    }
}

/**
 * Container do leitor — HorizontalPager entre capítulos (paginação interna é
 * via rolagem vertical do WebView). Mostra "pág. X/Y · cap. A/B" no rodapé.
 *
 * Navegação entre capítulos: swipe vertical no fim/início do capítulo dispara
 * `onCrossChapter` via JS bridge (ver wrapHtml). Não temos botões de pular —
 * o swipe natural já cobre.
 */
@Composable
private fun PagedReader(
    pager: PagerState,
    pageInChapter: Int,
    pagesInChapter: Int,
    pageContent: @Composable (Int) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize()
        ) { index -> pageContent(index) }

        val label = buildString {
            if (pagesInChapter > 1) {
                append("pág. ")
                append((pageInChapter + 1).coerceAtLeast(1))
                append('/')
                append(pagesInChapter)
                append(" • ")
            }
            append("cap. ")
            append(pager.currentPage + 1)
            append('/')
            append(pager.pageCount)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 1.dp)
        )
    }
}

private const val EPUB_BASE_HOST = "munux.local"

private fun baseUrlForChapter(href: String): String {
    val dir = href.substringBeforeLast('/', "")
    return if (dir.isEmpty()) "https://$EPUB_BASE_HOST/"
    else "https://$EPUB_BASE_HOST/$dir/"
}

@Composable
private fun ReaderModeMenu(current: ReaderMode, onSelect: (ReaderMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Translate, contentDescription = "Modo de leitura")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReaderMode.values().forEach { m ->
                DropdownMenuItem(
                    text = { Text(modeLabel(m) + if (m == current) "  ✓" else "") },
                    onClick = { open = false; onSelect(m) }
                )
            }
        }
    }
}

private fun modeLabel(m: ReaderMode): String = when (m) {
    ReaderMode.Original -> "Original"
    ReaderMode.Bilingual -> "Bilíngue"
    ReaderMode.Translated -> "Traduzido"
}

private fun stripChromeTags(body: String): String = body
    .replace(Regex("(?is)<\\?xml[^?]*\\?>"), "")
    .replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
    .replace(Regex("(?is)</?html[^>]*>"), "")
    .replace(Regex("(?is)<head>.*?</head>"), "")
    .replace(Regex("(?is)</?body[^>]*>"), "")
    // Tira <script>/<style>/<link>/<meta> soltos no corpo — alguns EPUBs têm CSS
    // inline com regras `color: transparent` ou folhas de estilo externas que
    // sobrepoem nossas cores e deixam o texto invisível.
    .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
    .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
    .replace(Regex("(?is)<link[^>]*>"), "")
    .replace(Regex("(?is)<meta[^>]*>"), "")

private fun buildBilingualBody(original: String, translated: String?): String {
    val o = stripChromeTags(original)
    val t = translated?.let { stripChromeTags(it) }
    val translatedSection = if (t.isNullOrBlank()) {
        """<p class="munux-section-label">(tradução indisponível para este capítulo)</p>"""
    } else {
        """
        <hr class="munux-divider"/>
        <p class="munux-section-label">Tradução</p>
        <section lang="pt-BR">$t</section>
        """.trimIndent()
    }
    return """
        <p class="munux-section-label">Original</p>
        <section>$o</section>
        $translatedSection
    """.trimIndent()
}

/** Cores resolvidas para cada tema. Documentado em docs/leitor-decisoes.md §10. */
private data class ThemeColors(val bg: String, val fg: String, val link: String)

private fun colorsFor(theme: ReaderTheme): ThemeColors = when (theme) {
    ReaderTheme.LIGHT -> ThemeColors(bg = "#fdfdfb", fg = "#202225", link = "#1565c0")
    ReaderTheme.SEPIA -> ThemeColors(bg = "#f4ecd8", fg = "#3b2f1c", link = "#7c4a00")
    ReaderTheme.DARK -> ThemeColors(bg = "#1a1d24", fg = "#d8d4cc", link = "#90caf9")
}

private fun wrapHtml(body: String, prefs: ReaderPrefs): String {
    val c = colorsFor(prefs.theme)
    val cleanedBody = stripChromeTags(body)
    val sideMargin = prefs.sideMarginDp
    // Engine de paginação baseado em rolagem vertical com snap por altura de viewport.
    //
    // Por que não multi-column horizontal? O WebView do Android tem bug de layout
    // com `column-width: 100vw` em conteúdo de EPUB: o primeiro elemento acaba
    // posicionado em x=411 (coluna 2) ao invés de x=0 (coluna 1), deixando a
    // primeira página em branco. Diagnosticado via console.log do
    // `getBoundingClientRect()` em 2026-05-19.
    //
    // Vertical scroll é simples e robusto:
    //   - O body rola verticalmente; cada "página" é uma altura de viewport.
    //   - Posição = scrollTop / (scrollHeight - innerHeight).
    //   - Swipe vertical natural + snap manual no touchend.
    //
    // Decisão documentada em docs/leitor-decisoes.md §1.
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
        <style>
        html, body {
            margin: 0; padding: 0;
            background: ${c.bg};
            color: ${c.fg};
            -webkit-text-size-adjust: 100%;
        }
        html { height: 100%; }
        body {
            overflow-y: auto;
            overflow-x: hidden;
            overscroll-behavior: contain;
            -webkit-user-select: none;
            user-select: none;
            scrollbar-width: none;
        }
        body::-webkit-scrollbar { display: none; }
        #munux-pages {
            box-sizing: border-box;
            font-family: serif;
            font-size: ${prefs.fontSizeSp}px;
            line-height: ${prefs.lineHeight};
            padding: 14px ${sideMargin}px 80px ${sideMargin}px;
        }
        p { margin: 0 0 1em 0; orphans: 3; widows: 3; }
        h1, h2, h3, h4, h5, h6 { margin: 1em 0 0.5em 0; }
        img {
            max-width: 100%;
            height: auto;
            display: block;
            margin: 0.6em auto;
        }
        a { color: ${c.link}; text-decoration: underline; }
        hr.munux-divider {
            border: 0; border-top: 1px dashed ${c.fg}; opacity: 0.4;
            margin: 32px 0 16px 0;
        }
        .munux-section-label {
            font-family: sans-serif; font-size: 0.78em; letter-spacing: 1px;
            text-transform: uppercase; opacity: 0.6; margin: 14px 0 12px 0;
        }
        /* Garante visibilidade — alguns EPUBs trazem CSS interno que sobrepoe a
           cor do texto ou esconde elementos. Forçamos cor do tema em tudo. */
        #munux-pages, #munux-pages * {
            color: ${c.fg} !important;
            visibility: visible !important;
            opacity: 1 !important;
        }
        </style>
        </head>
        <body><div id="munux-pages">$cleanedBody</div>
        <script>
        (function() {
          var stage = document.getElementById('munux-pages');
          if (!stage) return;
          var H = function(){ return window.innerHeight; };
          function maxScroll(){
            return Math.max(0, document.documentElement.scrollHeight - H());
          }
          function totalPages(){
            return Math.max(1, Math.ceil(document.documentElement.scrollHeight / H()));
          }
          function currentPageNum(){
            return Math.round(window.scrollY / H());
          }
          function ratio(){
            var m = maxScroll();
            return m <= 0 ? 0 : window.scrollY / m;
          }
          function report(){
            if (window.MunuxBridge) {
              try { window.MunuxBridge.onPage(currentPageNum(), totalPages(), ratio()); } catch(e){}
            }
          }
          function snapTo(page, smooth){
            var t = totalPages();
            page = Math.max(0, Math.min(page, t - 1));
            window.scrollTo({ left: 0, top: page * H(), behavior: smooth ? 'smooth' : 'auto' });
            setTimeout(report, smooth ? 300 : 30);
          }

          // Reporta posição enquanto a usuária rola (debounce 200ms) — pra
          // memória de posição funcionar mesmo sem snap.
          var reportTimer;
          window.addEventListener('scroll', function(){
            clearTimeout(reportTimer);
            reportTimer = setTimeout(report, 200);
          }, { passive: true });

          // Snap só em swipe DELIBERADO (rápido + longo). Drag de leitura
          // suave deixa parado onde a usuária quer ler.
          var touchStartY = 0;
          var touchStartTime = 0;
          var touchStartScrollY = 0;
          document.addEventListener('touchstart', function(e){
            touchStartY = e.touches[0].clientY;
            touchStartTime = Date.now();
            touchStartScrollY = window.scrollY;
          }, { passive: true });
          document.addEventListener('touchend', function(e){
            var dy = e.changedTouches[0].clientY - touchStartY;
            var dt = Date.now() - touchStartTime;
            var absDy = Math.abs(dy);
            var m = maxScroll();
            // Cross-chapter: dedo arrastou pra cima no fim, ou pra baixo no início.
            if (window.scrollY >= m - 4 && dy < -H() * 0.25) {
              if (window.MunuxBridge) try { window.MunuxBridge.onCrossChapter(1); } catch(e){}
              return;
            }
            if (window.scrollY <= 4 && dy > H() * 0.25) {
              if (window.MunuxBridge) try { window.MunuxBridge.onCrossChapter(-1); } catch(e){}
              return;
            }
            // Swipe deliberado = rápido (<350ms) E longo (>30% da viewport).
            // Caso contrário deixa onde a usuária soltou o dedo.
            var deliberate = dt < 350 && absDy > H() * 0.30;
            if (deliberate) {
              var dir = dy < 0 ? 1 : -1;
              var fromPage = Math.round(touchStartScrollY / H());
              snapTo(fromPage + dir, true);
            }
          }, { passive: true });

          var initialRatio = __MUNUX_INITIAL_RATIO__;
          function restore(){
            var m = maxScroll();
            if (m > 0 && initialRatio > 0) {
              window.scrollTo({ left: 0, top: initialRatio * m, behavior: 'auto' });
            }
            report();
          }
          if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(function(){ setTimeout(restore, 30); });
          } else if (document.readyState === 'complete') {
            setTimeout(restore, 50);
          } else {
            window.addEventListener('load', function(){ setTimeout(restore, 50); });
          }

          var resizeTimer;
          window.addEventListener('resize', function(){
            var r = ratio();
            clearTimeout(resizeTimer);
            resizeTimer = setTimeout(function(){
              var m = maxScroll();
              window.scrollTo({ left: 0, top: r * m, behavior: 'auto' });
              report();
            }, 80);
          });
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun PdfReaderView(
    book: Book,
    translatedText: Map<Int, String>,
    mode: ReaderMode,
    onPageChanged: (Int) -> Unit,
    onTotalPagesResolved: (Int) -> Unit
) {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }

    DisposableEffect(book.id) {
        try {
            val file = File(book.filePath)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(descriptor)
            pfd = descriptor
            renderer = r
            pageCount = r.pageCount
            onTotalPagesResolved(r.pageCount)
        } catch (t: Throwable) {
            failure = "Não foi possível abrir o PDF: ${t.message}"
        }
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null
            pfd = null
        }
    }

    val err = failure
    if (err != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(err) }
        return
    }
    val r = renderer ?: return
    if (pageCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("PDF vazio") }
        return
    }

    val pager = rememberPagerState(
        initialPage = book.currentPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }
            .distinctUntilChanged()
            .debounce(400)
            .collect { onPageChanged(it) }
    }

    PagedReader(pager, pageInChapter = 0, pagesInChapter = 1) { index ->
        val translated = translatedText[index]
        when (mode) {
            ReaderMode.Original -> PdfPage(renderer = r, index = index)
            ReaderMode.Translated -> {
                if (!translated.isNullOrBlank()) PdfTranslatedPage(text = translated)
                else PdfPage(renderer = r, index = index)
            }
            ReaderMode.Bilingual -> PdfBilingualPage(
                renderer = r,
                index = index,
                translated = translated
            )
        }
    }
}

@Composable
private fun PdfBilingualPage(renderer: PdfRenderer, index: Int, translated: String?) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index) {
        bitmap = withContext(Dispatchers.IO) {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val scale = 2
                    val bmp = Bitmap.createBitmap(
                        page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(AndroidColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }
    val bmp = bitmap
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (bmp == null) {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        Text(
            text = "Tradução",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)
        )
        Text(
            text = translated ?: "(tradução indisponível para esta página)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 16.dp)
        )
    }
}

@Composable
private fun PdfTranslatedPage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index) {
        bitmap = withContext(Dispatchers.IO) {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val scale = 2
                    val bmp = Bitmap.createBitmap(
                        page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(AndroidColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }
    val bmp = bitmap
    Box(
        modifier = Modifier.fillMaxSize().background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (bmp == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
