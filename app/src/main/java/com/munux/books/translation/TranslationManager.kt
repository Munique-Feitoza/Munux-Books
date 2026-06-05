package com.munux.books.translation

import android.content.Context
import com.munux.books.data.AppDatabase
import com.munux.books.data.Book
import com.munux.books.data.BookFormat
import com.munux.books.data.SettingsRepo
import com.munux.books.data.TranslationEntry
import com.munux.books.data.TranslationStatus
import com.munux.books.reader.EpubParser
import com.munux.books.reader.PdfTextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JobProgress(
    val bookId: Long,
    val total: Int,
    val done: Int,
    val running: Boolean,
    val error: String? = null,
    /** Estimativa de tempo restante em ms (null = ainda sem dados suficientes). */
    val etaMs: Long? = null,
    /** Texto do que está acontecendo agora (ex.: aguardando limite da API). */
    val note: String? = null
)

class TranslationManager private constructor(
    private val appContext: Context
) {
    private val db = AppDatabase.get(appContext)
    private val bookDao = db.bookDao()
    private val translationDao = db.translationDao()
    private val settings = SettingsRepo(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _jobs = MutableStateFlow<Map<Long, JobProgress>>(emptyMap())
    val jobs: StateFlow<Map<Long, JobProgress>> = _jobs.asStateFlow()

    private val running = mutableMapOf<Long, Job>()

    fun translateBook(bookId: Long) {
        synchronized(running) {
            if (running[bookId]?.isActive == true) return
            running[bookId] = scope.launch { runJob(bookId) }
        }
    }

    fun cancel(bookId: Long) {
        synchronized(running) {
            running[bookId]?.cancel()
            running.remove(bookId)
        }
    }

    private suspend fun runJob(bookId: Long) {
        val cfg = settings.current()
        val book = bookDao.getById(bookId) ?: return
        if (cfg.apiKey.isBlank()) {
            bookDao.updateTranslationStatus(
                bookId, TranslationStatus.FAILED, 0, cfg.targetLang,
                "Configure a API key (${cfg.provider.name}) em Configurações"
            )
            _jobs.update { it + (bookId to JobProgress(bookId, 0, 0, false, "API key ausente")) }
            return
        }

        val translator = TranslatorFactory.create(cfg)

        try {
            val units = loadUnits(book)
            if (units.isEmpty()) {
                bookDao.updateTranslationStatus(
                    bookId, TranslationStatus.FAILED, 0, cfg.targetLang,
                    "Não foi possível extrair texto do livro"
                )
                _jobs.update { it + (bookId to JobProgress(bookId, 0, 0, false, "Sem conteúdo")) }
                return
            }

            // Marca como "em andamento" mas mantém a contagem já existente — não zera,
            // senão o app some com a opção de ler os capítulos já traduzidos enquanto re-escaneia.
            var knownCount = book.translatedChapters.coerceIn(0, units.size)
            bookDao.updateTranslationStatus(
                bookId, TranslationStatus.IN_PROGRESS, knownCount, cfg.targetLang, null
            )

            var done = 0
            var eta: Long? = null
            var note: String? = null
            val report = {
                _jobs.update {
                    it + (bookId to JobProgress(bookId, units.size, done, true, null, eta, note))
                }
            }
            report()

            val pacer = Pacer(MIN_GAP_BETWEEN_CALLS_MS)
            val runStart = System.currentTimeMillis()
            var processed = 0
            for ((index, unit) in units.withIndex()) {
                currentCoroutineContext().ensureActive()

                val hash = sha1(unit.content)
                val existing = translationDao.get(bookId, index, cfg.targetLang)
                if (existing != null && existing.sourceHash == hash) {
                    done++
                    note = null
                    report()
                    if (done > knownCount) {
                        knownCount = done
                        bookDao.updateTranslationStatus(
                            bookId, TranslationStatus.IN_PROGRESS, knownCount, cfg.targetLang, null
                        )
                    }
                    continue
                }

                val translated = if (unit.content.isBlank()) {
                    ""
                } else {
                    note = "Traduzindo capítulo ${index + 1} de ${units.size}…"
                    report()
                    translateUnit(
                        translator, pacer, unit.kind, unit.content,
                        cfg.sourceLang, cfg.targetLang, depth = 0,
                        report = { n -> note = n; report() }
                    )
                }

                translationDao.upsert(
                    TranslationEntry(
                        bookId = bookId,
                        chapterIndex = index,
                        targetLang = cfg.targetLang,
                        sourceHash = hash,
                        translatedContent = translated
                    )
                )
                done++
                if (unit.content.isNotBlank()) {
                    processed++
                    val elapsed = System.currentTimeMillis() - runStart
                    eta = (elapsed / processed) * (units.size - done).coerceAtLeast(0)
                }
                note = null
                report()
                if (done > knownCount) knownCount = done
                bookDao.updateTranslationStatus(
                    bookId, TranslationStatus.IN_PROGRESS, knownCount, cfg.targetLang, null
                )
            }

            bookDao.updateTranslationStatus(
                bookId, TranslationStatus.DONE, done, cfg.targetLang, null
            )
            _jobs.update { it + (bookId to JobProgress(bookId, units.size, done, false)) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Cancelado pelo usuário: o status no banco continua IN_PROGRESS (= interrompido),
            // que a UI mostra como "retomável". Não marca como falha.
            _jobs.update { it - bookId }
            throw c
        } catch (t: Throwable) {
            val rateLimit = t as? RateLimitException ?: t.cause as? RateLimitException
            val friendly = friendlyError(t, rateLimit)
            bookDao.updateTranslationStatus(
                bookId, TranslationStatus.FAILED,
                bookDao.getById(bookId)?.translatedChapters ?: 0,
                cfg.targetLang, friendly
            )
            if (rateLimit != null) {
                bookDao.updateRetryAfter(bookId, System.currentTimeMillis() + retryWaitMs(rateLimit))
            }
            _jobs.update {
                val curr = it[bookId]
                it + (bookId to (curr?.copy(running = false, error = friendly)
                    ?: JobProgress(bookId, 0, 0, false, friendly)))
            }
        } finally {
            synchronized(running) { running.remove(bookId) }
        }
    }

    /**
     * Quanto esperar antes de tentar de novo, dado o retryDelay do provedor. Para um retryDelay
     * pequeno mas que estourou várias vezes seguidas, usa um piso de 2 min.
     */
    private fun retryWaitMs(t: RateLimitException): Long =
        if (t.retryAfterMs > MAX_REASONABLE_RETRY_MS) t.retryAfterMs
        else t.retryAfterMs.coerceAtLeast(2 * 60 * 1000L)

    private fun fmtDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(1)
        val min = totalSec / 60
        return when {
            min >= 60 -> "${min / 60}h ${min % 60}min"
            min >= 1 -> "$min min"
            else -> "$totalSec s"
        }
    }

    private fun friendlyError(t: Throwable, rateLimit: RateLimitException?): String {
        if (rateLimit != null) {
            val waitMs = retryWaitMs(rateLimit)
            val at = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(System.currentTimeMillis() + waitMs))
            val daily = rateLimit.retryAfterMs > MAX_REASONABLE_RETRY_MS
            return if (daily) {
                "Cota diária do provedor esgotada. O progresso está salvo. " +
                    "Espere ~${fmtDuration(waitMs)} (por volta das $at) e toque em Continuar."
            } else {
                "Limite de requisições atingido. O progresso está salvo. " +
                    "Espere ~${fmtDuration(waitMs)} (por volta das $at) e toque em Continuar."
            }
        }
        if (isNetworkError(t)) {
            return "Sem conexão com a internet. O progresso está salvo — " +
                "conecte e toque em Continuar tradução."
        }
        val translatorErr = t as? TranslatorException ?: t.cause as? TranslatorException
        val httpCode = translatorErr?.httpCode
        if (httpCode != null) {
            return when (httpCode) {
                402 -> "Saldo insuficiente na conta do provedor. Recarregue créditos no painel " +
                    "(ex.: platform.deepseek.com) e toque em Continuar tradução."
                503 -> "O provedor está com alta demanda no momento (503). O progresso está salvo — " +
                    "espere alguns minutos e toque em Continuar tradução."
                in 500..599 -> "O servidor do provedor está instável agora (erro $httpCode). O progresso está salvo — " +
                    "tente Continuar tradução daqui a alguns minutos."
                401, 403 -> "A API key foi recusada (erro $httpCode). Confira a chave em Configurações."
                400 -> "O provedor recusou a requisição (erro 400). Verifique a API key, o modelo e a base URL em Configurações."
                404 -> "Modelo ou endpoint não encontrado (erro 404). Verifique o modelo e a base URL em Configurações."
                // Para códigos não mapeados, mostra a mensagem original do provedor — costuma
                // ser mais útil que o genérico "Erro do provedor (HTTP X)".
                else -> translatorErr.message?.ifBlank { null }
                    ?: "Erro do provedor (HTTP $httpCode). O progresso está salvo — tente Continuar tradução mais tarde."
            }
        }
        return t.message?.ifBlank { null } ?: "Erro desconhecido"
    }

    private fun isNetworkError(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            when (e) {
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketTimeoutException,
                is java.net.NoRouteToHostException -> return true
            }
            if (e is java.io.IOException && e.message?.contains("Unable to resolve host", true) == true) return true
            e = e.cause
        }
        return false
    }

    /**
     * Traduz um trecho. Se o Gemini cortar a resposta por limite de tokens, divide o
     * trecho em pedaços menores e traduz cada um, juntando o resultado na ordem.
     */
    private suspend fun translateUnit(
        translator: Translator,
        pacer: Pacer,
        kind: UnitKind,
        content: String,
        source: String,
        target: String,
        depth: Int,
        report: (String?) -> Unit
    ): String {
        try {
            return translateWithRetry(translator, pacer, kind, content, source, target, report)
        } catch (t: TruncatedException) {
            val parts = if (depth < MAX_CHUNK_DEPTH) splitContent(kind, content) else emptyList()
            if (parts.size < 2) {
                // Não foi possível dividir mais. Devolve o que veio (melhor que perder tudo).
                if (t.partial.isNotBlank()) return t.partial
                throw TranslatorException("Trecho grande demais para traduzir de uma vez.", t)
            }
            report("Capítulo grande — traduzindo em ${parts.size} partes…")
            val sb = StringBuilder()
            for (p in parts) {
                currentCoroutineContext().ensureActive()
                sb.append(translateUnit(translator, pacer, kind, p, source, target, depth + 1, report))
            }
            return sb.toString()
        }
    }

    private suspend fun translateWithRetry(
        translator: Translator,
        pacer: Pacer,
        kind: UnitKind,
        content: String,
        source: String,
        target: String,
        report: (String?) -> Unit
    ): String {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < MAX_RETRIES) {
            try {
                pacer.await()
                return when (kind) {
                    UnitKind.HTML -> translator.translateHtml(content, source, target)
                    UnitKind.TEXT -> translator.translateText(content, source, target)
                }
            } catch (t: TruncatedException) {
                throw t // tratado por translateUnit (divide em pedaços)
            } catch (t: RateLimitException) {
                // retryDelay > 5min indica cota diária/horária esgotada — não adianta insistir.
                if (t.retryAfterMs > MAX_REASONABLE_RETRY_MS) throw t
                lastError = t
                attempt++
                val waitMs = t.retryAfterMs + 500L
                report("Limite da API atingido — aguardando ${(waitMs / 1000L).coerceAtLeast(1L)}s… (tentativa $attempt/$MAX_RETRIES)")
                // Respeita o retryDelay enviado pelo provedor + uma folga
                delay(waitMs)
            } catch (t: Throwable) {
                lastError = t
                attempt++
                // Erro 5xx (provedor sobrecarregado) costuma demorar mais para passar: espera mais.
                val isServerError = (t as? TranslatorException)?.httpCode?.let { it in 500..599 } == true
                val waitMs = if (isServerError) (5_000L * attempt).coerceAtMost(30_000L) else 1_500L * attempt
                val why = if (isServerError) "Provedor sobrecarregado" else "Erro temporário"
                report("$why — tentando de novo em ${waitMs / 1000L}s… (tentativa $attempt/$MAX_RETRIES)")
                delay(waitMs)
            }
        }
        throw lastError ?: TranslatorException("Falha desconhecida na tradução")
    }

    /** Divide um trecho em duas metades numa fronteira segura mais próxima do meio. */
    private fun splitContent(kind: UnitKind, content: String): List<String> {
        val boundaries = when (kind) {
            UnitKind.HTML -> listOf(
                "</p>", "</div>", "</section>", "</article>", "</li>",
                "</blockquote>", "</h1>", "</h2>", "</h3>", "</h4>", "</tr>", "\n"
            )
            UnitKind.TEXT -> listOf("\n\n", "\n", ". ")
        }
        val mid = content.length / 2
        for (b in boundaries) {
            var best = -1
            var bestDist = Int.MAX_VALUE
            var from = 0
            while (true) {
                val i = content.indexOf(b, from)
                if (i < 0) break
                val cut = i + b.length
                if (cut in 1 until content.length) {
                    val d = kotlin.math.abs(cut - mid)
                    if (d < bestDist) { bestDist = d; best = cut }
                }
                from = i + b.length
            }
            if (best > 0) return listOf(content.substring(0, best), content.substring(best))
        }
        return emptyList()
    }

    /** Garante o intervalo mínimo entre chamadas ao Gemini (throttle do free tier). */
    private class Pacer(private val minGapMs: Long) {
        private var lastAt = 0L
        suspend fun await() {
            if (lastAt > 0L) {
                val wait = minGapMs - (System.currentTimeMillis() - lastAt)
                if (wait > 0) delay(wait)
            }
            lastAt = System.currentTimeMillis()
        }
    }

    private suspend fun loadUnits(book: Book): List<TranslationUnit> = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        when (book.format) {
            BookFormat.EPUB -> EpubParser.read(file).chapters.map {
                TranslationUnit(UnitKind.HTML, it.html)
            }
            BookFormat.PDF -> PdfTextExtractor.extractPages(file).map {
                TranslationUnit(UnitKind.TEXT, it)
            }
            BookFormat.UNKNOWN -> emptyList()
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private enum class UnitKind { HTML, TEXT }
    private data class TranslationUnit(val kind: UnitKind, val content: String)

    companion object {
        // 9000ms = ~6–7 RPM. Bem abaixo de qualquer limite atual do free tier do Gemini.
        private const val MIN_GAP_BETWEEN_CALLS_MS = 9_000L
        private const val MAX_RETRIES = 10
        // Se o retryDelay for absurdo (>5min) é cota diária, não rolling-window.
        private const val MAX_REASONABLE_RETRY_MS = 5 * 60 * 1000L
        // Quantas vezes um capítulo pode ser dividido pela metade (até 2^4 = 16 pedaços).
        private const val MAX_CHUNK_DEPTH = 4

        @Volatile private var instance: TranslationManager? = null
        fun get(context: Context): TranslationManager =
            instance ?: synchronized(this) {
                instance ?: TranslationManager(context.applicationContext).also { instance = it }
            }
    }
}
