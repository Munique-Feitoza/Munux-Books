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
 * Implementação para a API nativa do Google Gemini
 * (generativelanguage.googleapis.com).
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash"
) : Translator {

    override suspend fun translateHtml(html: String, sourceLang: String, targetLang: String): String =
        translate(buildHtmlPrompt(sourceLang, targetLang, html))

    override suspend fun translateText(text: String, sourceLang: String, targetLang: String): String =
        translate(buildTextPrompt(sourceLang, targetLang, text))

    private suspend fun translate(prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw TranslatorException("API key do Gemini não configurada")
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        )

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("topP", 0.95)
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                // Gemini 2.5 Flash liga "thinking" por padrão e os tokens de raciocínio
                // são descontados do maxOutputTokens — desligamos para sobrar tudo p/ a tradução.
                if (model.contains("flash", ignoreCase = true)) {
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 0)
                        put("includeThoughts", false)
                    })
                }
            })
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use(BufferedReader::readText)
            }.orEmpty()

            if (code !in 200..299) {
                val errorObj = runCatching { JSONObject(response).optJSONObject("error") }.getOrNull()
                val errMsg = errorObj?.optString("message") ?: response.take(300)
                if (code == 429) {
                    val retryMs = parseRetryDelayMs(errorObj) ?: DEFAULT_RETRY_AFTER_MS
                    throw RateLimitException("Gemini HTTP 429: $errMsg", retryMs)
                }
                throw TranslatorException("Gemini HTTP $code: $errMsg", httpCode = code)
            }

            return@withContext extractText(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(json: String): String {
        val obj = runCatching { JSONObject(json) }.getOrElse {
            throw TranslatorException("Resposta Gemini inválida", it)
        }
        obj.optJSONObject("promptFeedback")?.optString("blockReason")
            ?.takeIf { it.isNotBlank() }
            ?.let { throw TranslatorException("Gemini bloqueou o conteúdo enviado: $it") }

        val candidates = obj.optJSONArray("candidates")
            ?: throw TranslatorException("Resposta Gemini sem candidates: ${json.take(300)}")
        if (candidates.length() == 0) throw TranslatorException("Gemini retornou vazio")

        val first = candidates.getJSONObject(0)
        val finishReason = first.optString("finishReason")
        val parts = first.optJSONObject("content")?.optJSONArray("parts")

        val builder = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                builder.append(parts.getJSONObject(i).optString("text"))
            }
        }
        val text = builder.toString().trim().stripCodeFence()

        when (finishReason) {
            "MAX_TOKENS" -> throw TruncatedException(text)
            "", "STOP", "FINISH_REASON_UNSPECIFIED" -> Unit
            else -> if (text.isBlank()) {
                throw TranslatorException("Gemini interrompeu a tradução (finishReason=$finishReason)")
            }
        }
        if (text.isBlank()) throw TranslatorException("Gemini retornou texto vazio")
        return text
    }

    private fun parseRetryDelayMs(errorObj: JSONObject?): Long? {
        if (errorObj == null) return null
        val details = errorObj.optJSONArray("details") ?: return null
        for (i in 0 until details.length()) {
            val item = details.optJSONObject(i) ?: continue
            val type = item.optString("@type")
            if (type.endsWith("RetryInfo")) {
                val raw = item.optString("retryDelay")
                if (raw.isNotBlank()) return parseDuration(raw)
            }
        }
        return null
    }

    private fun parseDuration(raw: String): Long? {
        // Formatos esperados: "31s", "1.5s", "0.250s"
        val cleaned = raw.trim().removeSuffix("s")
        val seconds = cleaned.toDoubleOrNull() ?: return null
        return (seconds * 1000).toLong()
    }

    companion object {
        private const val DEFAULT_RETRY_AFTER_MS = 30_000L
        // gemini-2.5-flash aceita até 65.536 tokens de saída — capítulo inteiro cabe.
        private const val MAX_OUTPUT_TOKENS = 65_536
    }
}

internal fun String.stripCodeFence(): String {
    val trimmed = trim()
    if (!trimmed.startsWith("```")) return trimmed
    val firstNewline = trimmed.indexOf('\n')
    if (firstNewline < 0) return trimmed
    val withoutOpen = trimmed.substring(firstNewline + 1)
    val closeIndex = withoutOpen.lastIndexOf("```")
    return if (closeIndex < 0) withoutOpen else withoutOpen.substring(0, closeIndex).trim()
}

internal fun buildHtmlPrompt(source: String, target: String, html: String): String =
    """
    Você é um tradutor literário profissional. Traduza o conteúdo HTML abaixo de $source para $target.

    Regras OBRIGATÓRIAS:
    - Preserve EXATAMENTE todas as tags HTML, atributos, classes, ids e estrutura.
    - Mantenha intactas as tags <img>, <svg>, <a>, <link>, <video>, <audio> e os atributos src, href, srcset, poster, data-*. NÃO altere caminhos relativos.
    - Traduza apenas o texto visível ao leitor (conteúdo dentro das tags e atributos como title, alt, aria-label).
    - Mantenha entidades HTML, espaçamento, quebras de linha e indentação razoavelmente similares.
    - NÃO adicione explicações, comentários ou marcações markdown como ```html.
    - Retorne apenas o HTML traduzido, sem nenhum texto antes ou depois.

    HTML original:
    $html
    """.trimIndent()

internal fun buildTextPrompt(source: String, target: String, text: String): String =
    """
    Traduza o texto a seguir de $source para $target. Mantenha quebras de parágrafo.
    Retorne SOMENTE a tradução, sem comentários, sem prefixos, sem markdown.

    Texto:
    $text
    """.trimIndent()
