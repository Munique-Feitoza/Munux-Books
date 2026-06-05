package com.munux.books.ui

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munux.books.data.AppSettings
import com.munux.books.data.SettingsRepo
import com.munux.books.data.TranslatorProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepo(app)

    val settings = repo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    suspend fun save(updated: AppSettings) {
        repo.update { updated }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val current by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(current.provider) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var model by remember { mutableStateOf(current.model) }
    var sourceLang by remember { mutableStateOf(current.sourceLang) }
    var targetLang by remember { mutableStateOf(current.targetLang) }
    var showKey by remember { mutableStateOf(false) }

    LaunchedEffect(current) {
        provider = current.provider
        apiKey = current.apiKey
        baseUrl = current.baseUrl
        model = current.model
        sourceLang = current.sourceLang
        targetLang = current.targetLang
    }

    val showBaseUrl = provider != TranslatorProvider.GEMINI

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Provedor de tradução", style = MaterialTheme.typography.titleMedium)
            Text(
                "Escolha um preset para preencher provedor, base URL e modelo " +
                    "automaticamente. Depois, cole sua API key abaixo.",
                style = MaterialTheme.typography.bodySmall
            )

            // Presets clicáveis — preenchem provider+baseUrl+model num clique só.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsRepo.PRESETS.forEach { preset ->
                    val selected = preset.provider == provider &&
                        preset.baseUrl.trimEnd('/') == baseUrl.trimEnd('/') &&
                        preset.model == model
                    FilterChip(
                        selected = selected,
                        onClick = {
                            provider = preset.provider
                            baseUrl = preset.baseUrl
                            model = preset.model
                            // A chave não é alterada — o usuário cola a dele.
                        },
                        label = { Text(preset.label) }
                    )
                }
            }

            Text(
                "Tipo: ${providerLabel(provider)}",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("API key") },
                supportingText = { Text(apiKeyHint(provider)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )

            if (showBaseUrl) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it.trim() },
                    label = { Text("Base URL") },
                    supportingText = { Text(baseUrlHint(provider)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = model,
                onValueChange = { model = it.trim() },
                label = { Text("Modelo") },
                supportingText = { Text(modelHint(provider)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = sourceLang,
                onValueChange = { sourceLang = it.trim() },
                label = { Text("Idioma de origem") },
                supportingText = { Text("ex: en, es, fr") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = targetLang,
                onValueChange = { targetLang = it.trim() },
                label = { Text("Idioma de destino") },
                supportingText = { Text("ex: pt-BR, es") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    scope.launch {
                        val resolvedBaseUrl = baseUrl.ifBlank {
                            SettingsRepo.defaultBaseUrlFor(provider)
                        }
                        val resolvedModel = model.ifBlank {
                            SettingsRepo.defaultModelFor(provider)
                        }
                        vm.save(
                            AppSettings(
                                provider = provider,
                                apiKey = apiKey,
                                baseUrl = resolvedBaseUrl,
                                model = resolvedModel,
                                sourceLang = sourceLang.ifBlank { "en" },
                                targetLang = targetLang.ifBlank { "pt-BR" }
                            )
                        )
                        snackbar.showSnackbar("Configurações salvas")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Salvar") }
        }
    }
}

private fun providerLabel(p: TranslatorProvider): String = when (p) {
    TranslatorProvider.GEMINI -> "Gemini (Google)"
    TranslatorProvider.OPENAI_COMPATIBLE -> "OpenAI-compatível"
    TranslatorProvider.ANTHROPIC -> "Anthropic (Claude)"
}

private fun apiKeyHint(p: TranslatorProvider): String = when (p) {
    TranslatorProvider.GEMINI -> "Pegue em aistudio.google.com/apikey"
    TranslatorProvider.OPENAI_COMPATIBLE -> "Chave do provedor selecionado (DeepSeek, OpenAI, Groq, etc.)"
    TranslatorProvider.ANTHROPIC -> "Chave em console.anthropic.com"
}

private fun baseUrlHint(p: TranslatorProvider): String = when (p) {
    TranslatorProvider.GEMINI -> ""
    TranslatorProvider.OPENAI_COMPATIBLE ->
        "Sem o /v1 no fim. Ex.: https://api.deepseek.com, https://api.openai.com"
    TranslatorProvider.ANTHROPIC -> "Geralmente https://api.anthropic.com"
}

private fun modelHint(p: TranslatorProvider): String = when (p) {
    TranslatorProvider.GEMINI -> "ex: gemini-2.5-flash, gemini-2.0-flash"
    TranslatorProvider.OPENAI_COMPATIBLE ->
        "ex: deepseek-chat, gpt-4o-mini, llama-3.3-70b-versatile"
    TranslatorProvider.ANTHROPIC -> "ex: claude-haiku-4-5, claude-sonnet-4-5"
}
