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
 * Translator para a API nativa Messages da Anthropic (Claude).
 *
 *   POST {baseUrl}/v1/messages
 *   x-api-key: <apiKey>
 *   anthropic-version: 2023-06-01
 */
class AnthropicTranslator(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String
) : Translator {

    override suspend fun translateHtml(html: String, sourceLang: String, targetLang: String): String =
        translate(buildHtmlPrompt(sourceLang, targetLang, html))

    override suspend fun translateText(text: String, sourceLang: String, targetLang: String): String =
        translate(buildTextPrompt(sourceLang, targetLang, text))

    private suspend fun translate(prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw TranslatorException("API key da Anthropic não configurada")
        val url = URL("${baseUrl.trimEnd('/')}/v1/messages")

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("temperature", 0.2)
            put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", prompt)
            ))
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
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
                    val retryMs = conn.getHeaderField("retry-after")?.toLongOrNull()?.times(1000)
                        ?: DEFAULT_RETRY_AFTER_MS
                    throw RateLimitException("Anthropic HTTP 429: $errMsg", retryMs)
                }
                throw TranslatorException("Anthropic HTTP $code: $errMsg", httpCode = code)
            }

            return@withContext extractText(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(json: String): String {
        val obj = runCatching { JSONObject(json) }.getOrElse {
            throw TranslatorException("Resposta Anthropic inválida", it)
        }
        val stopReason = obj.optString("stop_reason")
        val content = obj.optJSONArray("content")
            ?: throw TranslatorException("Resposta Anthropic sem 'content': ${json.take(300)}")

        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                builder.append(block.optString("text"))
            }
        }
        val text = builder.toString().trim().stripCodeFence()

        if (stopReason == "max_tokens") throw TruncatedException(text)
        if (text.isBlank()) throw TranslatorException("Anthropic retornou texto vazio")
        return text
    }

    private fun parseErrorMessage(response: String): String {
        val obj = runCatching { JSONObject(response) }.getOrNull() ?: return ""
        obj.optJSONObject("error")?.let { err ->
            val msg = err.optString("message")
            if (msg.isNotBlank()) return msg
        }
        return ""
    }

    companion object {
        private const val MAX_OUTPUT_TOKENS = 8_192
        private const val DEFAULT_RETRY_AFTER_MS = 30_000L
    }
}
