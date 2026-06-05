package com.munux.books.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "munux_settings")

enum class TranslatorProvider {
    /** API nativa do Google (generativelanguage.googleapis.com). */
    GEMINI,

    /** Endpoint OpenAI-compatível: cobre DeepSeek, OpenAI, Groq, OpenRouter, Mistral,
     *  Together, Ollama local, vLLM... a maioria das APIs modernas suporta. */
    OPENAI_COMPATIBLE,

    /** API nativa Messages da Anthropic (api.anthropic.com/v1/messages). */
    ANTHROPIC;

    companion object {
        fun parse(name: String?): TranslatorProvider =
            runCatching { valueOf(name ?: GEMINI.name) }.getOrDefault(GEMINI)
    }
}

data class AppSettings(
    val provider: TranslatorProvider = TranslatorProvider.GEMINI,
    /** Chave da API do provider atualmente selecionado. */
    val apiKey: String = "",
    /** Base URL (usado para OPENAI_COMPATIBLE). Ex.: https://api.deepseek.com */
    val baseUrl: String = "",
    /** Nome do modelo. Ex.: gemini-2.5-flash, deepseek-chat, gpt-4o-mini. */
    val model: String = "gemini-2.5-flash",
    val sourceLang: String = "en",
    val targetLang: String = "pt-BR"
) {
    /** Compatibilidade: telas antigas que ainda lêem `geminiApiKey`. */
    val geminiApiKey: String get() = if (provider == TranslatorProvider.GEMINI) apiKey else ""
}

class SettingsRepo(private val context: Context) {

    private object Keys {
        val Provider = stringPreferencesKey("provider")
        val ApiKey = stringPreferencesKey("api_key")
        val BaseUrl = stringPreferencesKey("base_url")
        val Model = stringPreferencesKey("model")
        val SourceLang = stringPreferencesKey("source_lang")
        val TargetLang = stringPreferencesKey("target_lang")

        // Versões antigas só tinham Gemini e gravavam em "gemini_api_key".
        // Lemos pra migrar automaticamente sem perder a chave.
        val LegacyGeminiApiKey = stringPreferencesKey("gemini_api_key")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[Keys.Provider] = updated.provider.name
            prefs[Keys.ApiKey] = updated.apiKey
            prefs[Keys.BaseUrl] = updated.baseUrl
            prefs[Keys.Model] = updated.model
            prefs[Keys.SourceLang] = updated.sourceLang
            prefs[Keys.TargetLang] = updated.targetLang
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val provider = TranslatorProvider.parse(this[Keys.Provider])
        val newApiKey = this[Keys.ApiKey]
        val legacyKey = this[Keys.LegacyGeminiApiKey].orEmpty()

        val apiKey = when {
            !newApiKey.isNullOrEmpty() -> newApiKey
            provider == TranslatorProvider.GEMINI && legacyKey.isNotEmpty() -> legacyKey
            else -> ""
        }

        return AppSettings(
            provider = provider,
            apiKey = apiKey,
            baseUrl = this[Keys.BaseUrl].orEmpty().ifBlank { defaultBaseUrlFor(provider) },
            model = this[Keys.Model] ?: defaultModelFor(provider),
            sourceLang = this[Keys.SourceLang] ?: "en",
            targetLang = this[Keys.TargetLang] ?: "pt-BR"
        )
    }

    companion object {
        fun defaultModelFor(provider: TranslatorProvider): String = when (provider) {
            TranslatorProvider.GEMINI -> "gemini-2.5-flash"
            TranslatorProvider.OPENAI_COMPATIBLE -> "deepseek-chat"
            TranslatorProvider.ANTHROPIC -> "claude-haiku-4-5"
        }

        fun defaultBaseUrlFor(provider: TranslatorProvider): String = when (provider) {
            TranslatorProvider.GEMINI -> ""
            TranslatorProvider.OPENAI_COMPATIBLE -> "https://api.deepseek.com"
            TranslatorProvider.ANTHROPIC -> "https://api.anthropic.com"
        }

        /** Presets prontos pra preencher Provider/baseUrl/model com 1 toque na UI. */
        val PRESETS: List<Preset> = listOf(
            Preset("Gemini", TranslatorProvider.GEMINI, "", "gemini-2.5-flash"),
            Preset("DeepSeek", TranslatorProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-chat"),
            Preset("OpenAI", TranslatorProvider.OPENAI_COMPATIBLE, "https://api.openai.com", "gpt-4o-mini"),
            Preset("Groq", TranslatorProvider.OPENAI_COMPATIBLE, "https://api.groq.com/openai", "llama-3.3-70b-versatile"),
            Preset("OpenRouter", TranslatorProvider.OPENAI_COMPATIBLE, "https://openrouter.ai/api", "openai/gpt-4o-mini"),
            Preset("Mistral", TranslatorProvider.OPENAI_COMPATIBLE, "https://api.mistral.ai", "mistral-small-latest"),
            Preset("Ollama local", TranslatorProvider.OPENAI_COMPATIBLE, "http://192.168.1.100:11434", "llama3.1:8b"),
            Preset("Anthropic Claude", TranslatorProvider.ANTHROPIC, "https://api.anthropic.com", "claude-haiku-4-5")
        )

        data class Preset(
            val label: String,
            val provider: TranslatorProvider,
            val baseUrl: String,
            val model: String
        )
    }
}
