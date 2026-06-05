package com.munux.books

import android.app.Application
import com.munux.books.data.AppSettings
import com.munux.books.data.SettingsRepo
import com.munux.books.data.TranslatorProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MunuxBooksApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        seedSettingsFromBuildConfig()
    }

    /**
     * Popula as Configurações com o que estiver em `local.properties` na primeira
     * instalação (ou enquanto o usuário ainda não tiver colado uma chave manualmente).
     *
     * Ordem de prioridade dos atalhos:
     *   1. TRANSLATOR_* (configuração explícita)
     *   2. DEEPSEEK_API_KEY  → OPENAI_COMPATIBLE @ api.deepseek.com / deepseek-chat
     *   3. OPENAI_API_KEY    → OPENAI_COMPATIBLE @ api.openai.com  / gpt-4o-mini
     *   4. ANTHROPIC_API_KEY → ANTHROPIC         / claude-haiku-4-5
     *   5. GEMINI_API_KEY    → GEMINI            / gemini-2.5-flash
     */
    private fun seedSettingsFromBuildConfig() {
        val repo = SettingsRepo(applicationContext)
        scope.launch {
            val current = repo.current()
            if (current.apiKey.isNotBlank()) return@launch // usuário já configurou

            val seed = pickSeed() ?: return@launch
            val sourceLang = BuildConfig.TRANSLATOR_SOURCE_LANG.ifBlank { current.sourceLang }
            val targetLang = BuildConfig.TRANSLATOR_TARGET_LANG.ifBlank { current.targetLang }
            repo.update {
                AppSettings(
                    provider = seed.provider,
                    apiKey = seed.apiKey,
                    baseUrl = seed.baseUrl,
                    model = seed.model,
                    sourceLang = sourceLang,
                    targetLang = targetLang
                )
            }
        }
    }

    private fun pickSeed(): Seed? {
        // 1. TRANSLATOR_* — usuário definiu tudo explicitamente.
        val explicitKey = BuildConfig.TRANSLATOR_API_KEY
        if (explicitKey.isNotBlank()) {
            val provider = runCatching {
                TranslatorProvider.valueOf(BuildConfig.TRANSLATOR_PROVIDER)
            }.getOrDefault(TranslatorProvider.OPENAI_COMPATIBLE)
            return Seed(
                provider = provider,
                apiKey = explicitKey,
                baseUrl = BuildConfig.TRANSLATOR_BASE_URL.ifBlank {
                    SettingsRepo.defaultBaseUrlFor(provider)
                },
                model = BuildConfig.TRANSLATOR_MODEL.ifBlank {
                    SettingsRepo.defaultModelFor(provider)
                }
            )
        }
        // 2. Atalhos por provedor.
        BuildConfig.DEEPSEEK_API_KEY.takeIf { it.isNotBlank() }?.let {
            return Seed(TranslatorProvider.OPENAI_COMPATIBLE, it, "https://api.deepseek.com", "deepseek-chat")
        }
        BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }?.let {
            return Seed(TranslatorProvider.OPENAI_COMPATIBLE, it, "https://api.openai.com", "gpt-4o-mini")
        }
        BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }?.let {
            return Seed(TranslatorProvider.ANTHROPIC, it, "https://api.anthropic.com", "claude-haiku-4-5")
        }
        // 3. Legado: chave do Gemini.
        BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }?.let {
            return Seed(
                TranslatorProvider.GEMINI,
                it,
                "",
                BuildConfig.GEMINI_MODEL.ifBlank { "gemini-2.5-flash" }
            )
        }
        return null
    }

    private data class Seed(
        val provider: TranslatorProvider,
        val apiKey: String,
        val baseUrl: String,
        val model: String
    )
}
