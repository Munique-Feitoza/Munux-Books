package com.munux.books.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munux.books.data.AppDatabase
import com.munux.books.data.Book
import com.munux.books.data.TranslationStatus
import com.munux.books.reader.BookImporter
import com.munux.books.translation.TranslationManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).bookDao()
    private val translationDao = AppDatabase.get(app).translationDao()
    private val translation = TranslationManager.get(app)

    val books = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val translationJobs = translation.jobs

    fun import(uri: Uri) {
        viewModelScope.launch {
            val book = BookImporter.import(getApplication(), uri)
            dao.insert(book)
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            translation.cancel(book.id)
            translationDao.deleteForBook(book.id)
            runCatching { File(book.filePath).delete() }
            dao.delete(book)
        }
    }

    fun translate(book: Book) {
        translation.translateBook(book.id)
    }

    fun cancelTranslation(book: Book) {
        translation.cancel(book.id)
    }

    fun resetTranslation(book: Book) {
        viewModelScope.launch {
            translation.cancel(book.id)
            translationDao.deleteForBook(book.id)
            dao.updateTranslationStatus(
                book.id, TranslationStatus.NONE, 0, null, null
            )
        }
    }

    /** Apaga as traduções existentes e recomeça do zero (descarta capítulos truncados). */
    fun retranslate(book: Book) {
        viewModelScope.launch {
            translation.cancel(book.id)
            translationDao.deleteForBook(book.id)
            dao.updateTranslationStatus(book.id, TranslationStatus.NONE, 0, null, null)
            translation.translateBook(book.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenBook: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val books by vm.books.collectAsStateWithLifecycle()
    val jobs by vm.translationJobs.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::import) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Munux Books") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                picker.launch(arrayOf("application/epub+zip", "application/pdf", "*/*"))
            }) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar livro")
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Toque em + para adicionar um livro",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        liveProgress = jobs[book.id],
                        onClick = { onOpenBook(book.id) },
                        onDelete = { vm.delete(book) },
                        onTranslate = { vm.translate(book) },
                        onCancelTranslation = { vm.cancelTranslation(book) },
                        onResetTranslation = { vm.resetTranslation(book) },
                        onRetranslate = { vm.retranslate(book) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    liveProgress: com.munux.books.translation.JobProgress?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTranslate: () -> Unit,
    onCancelTranslation: () -> Unit,
    onResetTranslation: () -> Unit,
    onRetranslate: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    // "running" = existe um job vivo agora. Se o status do banco é IN_PROGRESS mas não há
    // job vivo, a tradução foi interrompida (app/processo morreu) e precisa ser retomada.
    val running = liveProgress?.running == true

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val progress = if (book.totalPages > 0) {
                "${book.currentPage + 1}/${book.totalPages}"
            } else null
            val translation = translationLabel(book, running, liveProgress)
            val sub = listOfNotNull(book.author, book.format.name, progress, translation)
                .joinToString(" • ")
            Column(modifier = Modifier.fillMaxWidth()) {
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
                if (running) {
                    liveProgress?.note?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val total = liveProgress?.total ?: book.totalPages.coerceAtLeast(1)
                    val done = liveProgress?.done ?: book.translatedChapters
                    val frac = if (total > 0) done.toFloat() / total else 0f
                    LinearProgressIndicator(
                        progress = { frac.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
                book.retryAfterAt?.takeIf { !running }?.let { RetryCountdown(it) }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (running) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                            text = { Text("Cancelar tradução") },
                            onClick = { showMenu = false; onCancelTranslation() }
                        )
                    } else {
                        when (book.translationStatus) {
                            TranslationStatus.NONE -> DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                text = { Text("Traduzir") },
                                onClick = { showMenu = false; onTranslate() }
                            )
                            TranslationStatus.DONE -> DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                text = { Text("Retraduzir do zero") },
                                onClick = { showMenu = false; onRetranslate() }
                            )
                            else -> {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                    text = { Text("Continuar tradução") },
                                    onClick = { showMenu = false; onTranslate() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Retraduzir do zero") },
                                    onClick = { showMenu = false; onRetranslate() }
                                )
                            }
                        }
                    }
                    if (book.translationStatus != TranslationStatus.NONE) {
                        DropdownMenuItem(
                            text = { Text("Limpar tradução") },
                            onClick = { showMenu = false; onResetTranslation() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remover") },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    )
}

/** "12min", "1h 05min", "45s" — para mostrar tempo restante estimado. */
internal fun formatRemaining(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    return when {
        min >= 60 -> "${min / 60}h ${"%02d".format(min % 60)}min"
        min >= 1 -> "${min}min"
        else -> "${totalSec}s"
    }
}

/** "1:23:45" / "12:30" — contagem regressiva. */
internal fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Mostra "Cota liberando em mm:ss…" e atualiza a cada segundo. */
@Composable
internal fun RetryCountdown(availableAt: Long) {
    var now by remember(availableAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(availableAt) {
        while (now < availableAt) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val remaining = availableAt - now
    Text(
        if (remaining > 0) "Cota do Gemini liberando em ${formatClock(remaining)}…"
        else "Já pode tentar de novo — toque em ⋮ → Continuar tradução",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
}

private fun translationLabel(
    book: Book,
    running: Boolean,
    live: com.munux.books.translation.JobProgress?
): String? = when {
    running -> buildString {
        append("Traduzindo ${live?.done ?: book.translatedChapters}/${live?.total ?: book.totalPages.coerceAtLeast(1)}")
        live?.etaMs?.takeIf { it > 0 }?.let { append(" • ~${formatRemaining(it)} restantes") }
    }
    book.translationStatus == TranslationStatus.IN_PROGRESS ->
        "Tradução interrompida em ${book.translatedChapters}/${book.totalPages.coerceAtLeast(book.translatedChapters)} — toque em ⋮ para continuar"
    book.translationStatus == TranslationStatus.DONE ->
        "Traduzido${book.translationLang?.let { " ($it)" } ?: ""}"
    book.translationStatus == TranslationStatus.FAILED ->
        "Erro: ${book.translationError ?: "tradução falhou"}"
    else -> null
}
