package com.munux.books

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.munux.books.data.ReaderPrefs
import com.munux.books.data.ReaderPrefsRepo
import com.munux.books.ui.LibraryScreen
import com.munux.books.ui.ReaderScreen
import com.munux.books.ui.SettingsScreen
import com.munux.books.ui.theme.MunuxBooksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Lê o tema do leitor para aplicar globalmente — assim trocar pra Noite no
            // ônibus vale também pra biblioteca e configurações. Detalhes em
            // docs/leitor-decisoes.md §3 + §10.
            val ctx = LocalContext.current
            val prefsRepo = remember { ReaderPrefsRepo(ctx) }
            val prefs by prefsRepo.flow.collectAsState(initial = ReaderPrefs())

            MunuxBooksTheme(readerTheme = prefs.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "library") {
                        composable("library") {
                            LibraryScreen(
                                onOpenBook = { id -> nav.navigate("reader/$id") },
                                onOpenSettings = { nav.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                        composable(
                            route = "reader/{bookId}",
                            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                        ) { entry ->
                            val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                            ReaderScreen(bookId = bookId, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
