package com.munux.books.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Translator para qualquer endpoint OpenAI-compatível (Chat Completions).
 *
 * Cobre, entre outros: DeepSeek, OpenAI, Groq, OpenRouter, Mistral, Together,
 * Anthropic (modo compat), Ollama local, vLLM, llama.cpp server, LM Studio.
 *
 * Esquema esperado:
 *   POST {baseUrl}/v1/chat/completions
 *   Authorization: Bearer <apiKey>
 *   { "model": "...", "messages": [{"role":"user","content":"..."}], "max_tokens": N }
 *
 * Truncamento ([TruncatedException]) é detectado por finish_reason == "length".
 * 429 vira [RateLimitException] usando o header `Retry-After` quando presente.
 */
class OpenAICompatibleTranslator(
    private val apiKey: String,
    baseUrl: String,
    private val model: String
) : Translator {

    private val endpoint: String = buildEndpoint(baseUrl)

    override suspend fun translateHtml(html: String, sourceLang: String, targetLang: String): String =
        translate(buildHtmlPrompt(sourceLang, targetLang, html))

    override suspend fun translateText(text: String, sourceLang: String, targetLang: String): String =
        translate(buildTextPrompt(sourceLang, targetLang, text))

    private suspend fun translate(prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw TranslatorException("API key não configurada")
        val url = URL(endpoint)

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", prompt)
            ))
            put("temperature", 0.2)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("stream", false)
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use(BufferedReader::readText)
            }.orEmpty()

            if (code !in 200..299) {
                val errMsg = parseErrorMessage(response).ifBlank { response.take(300) }
                if (code == 429) {
                    val retryMs = readRetryAfterMs(conn) ?: DEFAULT_RETRY_AFTER_MS
                    throw RateLimitException("HTTP 429: $errMsg", retryMs)
                }
                throw TranslatorException("HTTP $code: $errMsg", httpCode = code)
            }

            return@withContext extractText(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(json: String): String {
        val obj = runCatching { JSONObject(json) }.getOrElse {
            throw TranslatorException("Resposta inválida", it)
        }
        val choices = obj.optJSONArray("choices")
            ?: throw TranslatorException("Resposta sem 'choices': ${json.take(300)}")
        if (choices.length() == 0) throw TranslatorException("Resposta retornou choices vazio")

        val first = choices.getJSONObject(0)
        val finishReason = first.optString("finish_reason")
        val message = first.optJSONObject("message")
        val raw = message?.optString("content").orEmpty()
        val text = raw.trim().stripCodeFence()

        when (finishReason) {
            "length" -> throw TruncatedException(text)
            "content_filter" -> if (text.isBlank())
                throw TranslatorException("Conteúdo bloqueado por filtro do provedor")
            // "stop", "tool_calls", "" — segue
        }
        if (text.isBlank()) throw TranslatorException("Provedor retornou texto vazio")
        return text
    }

    private fun parseErrorMessage(response: String): String {
        val obj = runCatching { JSONObject(response) }.getOrNull() ?: return ""
        // OpenAI/DeepSeek: { "error": { "message": "...", "type": "...", "code": "..." } }
        obj.optJSONObject("error")?.let { err ->
            val msg = err.optString("message")
            if (msg.isNotBlank()) return msg
        }
        // OpenRouter: às vezes { "error": { "message": "..." } } ou { "message": "..." }
        return obj.optString("message").orEmpty()
    }

    /** Lê `Retry-After` (segundos ou data HTTP). Default ~30s. */
    private fun readRetryAfterMs(conn: HttpURLConnection): Long? {
        val raw = conn.getHeaderField("Retry-After")?.trim() ?: return null
        // Caso 1: número inteiro de segundos
        raw.toLongOrNull()?.let { return it * 1000L }
        // Caso 2: data HTTP — usa o helper do HttpURLConnection
        val dateMs = conn.getHeaderFieldDate("Retry-After", -1L)
        if (dateMs > 0) {
            val wait = dateMs - System.currentTimeMillis()
            if (wait > 0) return wait
        }
        return null
    }

    companion object {
        // 8192 é o teto do DeepSeek-chat. OpenAI/Groq/etc. aceitam mais, mas como o
        // pedaço (chunk) que enviamos já é controlado pelo TranslationManager (que
        // divide quando dá truncado), esse limite é um chão seguro.
        private const val MAX_OUTPUT_TOKENS = 8_192
        private const val DEFAULT_RETRY_AFTER_MS = 30_000L

        /** Normaliza a baseUrl em um endpoint absoluto de chat completions. */
        internal fun buildEndpoint(baseUrl: String): String {
            val b = baseUrl.trim().trimEnd('/')
            if (b.isEmpty()) throw TranslatorException("Base URL não configurada")
            return when {
                b.endsWith("/chat/completions") -> b
                b.endsWith("/v1") -> "$b/chat/completions"
                else -> "$b/v1/chat/completions"
            }
        }
    }
}
