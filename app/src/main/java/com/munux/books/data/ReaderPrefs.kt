package com.munux.books.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore(name = "munux_reader_prefs")

/**
 * Tema visual do leitor.
 *
 * Decisões e referências em [docs/leitor-decisoes.md], seção 3 e 5.
 */
enum class ReaderTheme {
    LIGHT,   // fundo branco/quase, texto preto-azulado
    SEPIA,   // fundo creme, texto marrom-escuro
    DARK;    // fundo cinza-escuro (não preto puro), texto cinza-claro

    companion object {
        fun parse(name: String?): ReaderTheme =
            runCatching { valueOf(name ?: SEPIA.name) }.getOrDefault(SEPIA)
    }
}

/**
 * Preferências de aparência do leitor. Todas as escolhas e seus motivos estão
 * documentadas em [docs/leitor-decisoes.md].
 */
data class ReaderPrefs(
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    /** Tamanho da fonte em sp (escala de acessibilidade do Android). */
    val fontSizeSp: Int = 17,
    /** Altura de linha (multiplicador). */
    val lineHeight: Float = 1.55f,
    /** Margem lateral em dp. */
    val sideMarginDp: Int = 18,
    /** Filtro warm (ligeiramente amarelado) sobre qualquer tema. */
    val warmFilter: Boolean = false
)

class ReaderPrefsRepo(private val context: Context) {

    private object Keys {
        val Theme = stringPreferencesKey("theme")
        val FontSizeSp = intPreferencesKey("font_size_sp")
        val LineHeight = floatPreferencesKey("line_height")
        val SideMarginDp = intPreferencesKey("side_margin_dp")
        val WarmFilter = intPreferencesKey("warm_filter") // 0/1
    }

    val flow: Flow<ReaderPrefs> = context.readerDataStore.data.map { it.toPrefs() }

    suspend fun current(): ReaderPrefs = flow.first()

    suspend fun update(transform: (ReaderPrefs) -> ReaderPrefs) {
        context.readerDataStore.edit { prefs ->
            val updated = transform(prefs.toPrefs())
            prefs[Keys.Theme] = updated.theme.name
            prefs[Keys.FontSizeSp] = updated.fontSizeSp
            prefs[Keys.LineHeight] = updated.lineHeight
            prefs[Keys.SideMarginDp] = updated.sideMarginDp
            prefs[Keys.WarmFilter] = if (updated.warmFilter) 1 else 0
        }
    }

    private fun Preferences.toPrefs(): ReaderPrefs = ReaderPrefs(
        theme = ReaderTheme.parse(this[Keys.Theme]),
        fontSizeSp = this[Keys.FontSizeSp] ?: 17,
        lineHeight = this[Keys.LineHeight] ?: 1.55f,
        sideMarginDp = this[Keys.SideMarginDp] ?: 18,
        warmFilter = (this[Keys.WarmFilter] ?: 0) == 1
    )

    companion object {
        /**
         * Preset "🌅 Manhã (ônibus, sol pela janela)" — ver docs/leitor-decisoes.md §9.
         */
        val PRESET_MORNING = ReaderPrefs(
            theme = ReaderTheme.SEPIA,
            fontSizeSp = 18,
            lineHeight = 1.55f,
            sideMarginDp = 20,
            warmFilter = false
        )

        /**
         * Preset "🌙 Noite (ônibus voltando, luz baixa)" — ver docs/leitor-decisoes.md §9.
         */
        val PRESET_NIGHT = ReaderPrefs(
            theme = ReaderTheme.DARK,
            fontSizeSp = 19,
            lineHeight = 1.6f,
            sideMarginDp = 22,
            warmFilter = true
        )
    }
}
