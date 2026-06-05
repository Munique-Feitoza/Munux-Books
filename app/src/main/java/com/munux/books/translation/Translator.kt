package com.munux.books.translation

import com.munux.books.data.AppSettings
import com.munux.books.data.TranslatorProvider

/**
 * Contrato comum entre as implementações que falam com provedores diferentes de LLM
 * (Gemini nativo, OpenAI-compatível — DeepSeek/OpenAI/Groq/OpenRouter/Mistral/Ollama —,
 * Anthropic nativo).
 *
 * Todas as funções devem lançar uma [TranslatorException] (ou subclasse) em caso de erro.
 */
interface Translator {
    suspend fun translateHtml(html: String, sourceLang: String, targetLang: String): String
    suspend fun translateText(text: String, sourceLang: String, targetLang: String): String
}

open class TranslatorException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null
) : Exception(message, cause)

/** Erro 429 ou equivalente, com o atraso sugerido pelo provedor. */
class RateLimitException(
    message: String,
    val retryAfterMs: Long
) : TranslatorException(message, httpCode = 429)

/** Resposta cortada por limite de tokens. [partial] traz o que veio até o corte. */
class TruncatedException(val partial: String) :
    TranslatorException("Resposta truncada (limite de tokens atingido)")

/** Cria a implementação correta de [Translator] a partir das configurações do usuário. */
object TranslatorFactory {
    fun create(cfg: AppSettings): Translator = when (cfg.provider) {
        TranslatorProvider.GEMINI -> GeminiTranslator(cfg.apiKey, cfg.model)
        TranslatorProvider.OPENAI_COMPATIBLE -> OpenAICompatibleTranslator(
            apiKey = cfg.apiKey,
            baseUrl = cfg.baseUrl,
            model = cfg.model
        )
        TranslatorProvider.ANTHROPIC -> AnthropicTranslator(
            apiKey = cfg.apiKey,
            baseUrl = cfg.baseUrl.ifBlank { "https://api.anthropic.com" },
            model = cfg.model
        )
    }
}
