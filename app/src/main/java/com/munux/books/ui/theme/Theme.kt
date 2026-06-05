package com.munux.books.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.munux.books.data.ReaderTheme

/**
 * O app respeita o **tema escolhido na tela de Aparência do leitor** (Claro / Sépia /
 * Escuro) em TODAS as telas, não só na de leitura — assim trocar pra Noite no ônibus
 * vale também pra biblioteca e configurações. Quando não há preferência salva, segue o
 * sistema (claro/escuro do Android). Decisões em [docs/leitor-decisoes.md] §3 e §10.
 */
@Composable
fun MunuxBooksTheme(
    readerTheme: ReaderTheme? = null,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (readerTheme) {
        ReaderTheme.LIGHT -> LightScheme
        ReaderTheme.SEPIA -> SepiaScheme
        ReaderTheme.DARK -> DarkScheme
        null -> if (systemDark) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// Cores derivadas das três páginas-tipo escolhidas para o conteúdo
// (docs/leitor-decisoes.md §10) e adaptadas para Material 3.
//
// Decisão consciente: NÃO usamos dynamicColor (Material You / wallpaper) porque a
// escolha do leitor deve sobrescrever — senão o app fica visualmente inconsistente.

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5A4AB3),
    onPrimary = Color.White,
    secondary = Color(0xFF625B71),
    background = Color(0xFFFDFDFB),
    onBackground = Color(0xFF202225),
    surface = Color(0xFFFDFDFB),
    onSurface = Color(0xFF202225),
    surfaceVariant = Color(0xFFEEEEEC),
    onSurfaceVariant = Color(0xFF4A4A50)
)

// Sépia: fundo creme #f4ecd8, texto marrom-escuro #3b2f1c (docs/leitor-decisoes.md §10).
private val SepiaScheme = lightColorScheme(
    primary = Color(0xFF7C4A00),
    onPrimary = Color.White,
    secondary = Color(0xFF8C6A3B),
    background = Color(0xFFF4ECD8),
    onBackground = Color(0xFF3B2F1C),
    surface = Color(0xFFF4ECD8),
    onSurface = Color(0xFF3B2F1C),
    surfaceVariant = Color(0xFFE6DDC4),
    onSurfaceVariant = Color(0xFF5C4A30)
)

// Escuro: cinza-azulado #1a1d24 em vez de preto puro (docs/leitor-decisoes.md §5,
// referenciando Material Design dark theme guidelines #121212–#1f1f1f).
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0E1620),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF1A1D24),
    onBackground = Color(0xFFD8D4CC),
    surface = Color(0xFF1A1D24),
    onSurface = Color(0xFFD8D4CC),
    surfaceVariant = Color(0xFF272A33),
    onSurfaceVariant = Color(0xFFB8B5AE)
)
