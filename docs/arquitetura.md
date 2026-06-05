# Arquitetura

Visão geral dos módulos do Munux Books e de como os dados fluem entre eles. Para as decisões de UX/tipografia/tema do leitor, veja [leitor-decisoes.md](leitor-decisoes.md).

## Stack

- **UI:** Jetpack Compose (Material 3) + Navigation Compose.
- **Persistência:** Room (catálogo de livros) e DataStore Preferences (configurações e preferências do leitor).
- **Concorrência:** Kotlin Coroutines (`Dispatchers.IO` para I/O de arquivo e rede).
- **Leitura de arquivos:** parser próprio de EPUB (via `ZipFile` + DOM) e PDFBox-Android para PDF.
- **Build:** Gradle 8.9, AGP + Kotlin via *version catalog* (`gradle/libs.versions.toml`), KSP para o processador do Room. `applicationId = com.munux.books`, Java 17.

## Camadas

```
ui/  ──►  data/  ◄──  reader/
              ▲
              │
        translation/
```

### `MainActivity` / navegação

[MainActivity.kt](../app/src/main/java/com/munux/books/MainActivity.kt) define um `NavHost` com três destinos: `library` (inicial), `reader/{bookId}` e `settings`. O tema do leitor é lido do `DataStore` e aplicado globalmente, de modo que trocar para o preset *Noite* vale também para a biblioteca e as configurações.

### `data/` — persistência

- **`BookDb`** — banco Room com a entidade `Book` (título, autor, caminho do arquivo, formato, capítulo atual e proporção de rolagem da posição de leitura).
- **`Settings`** — `SettingsRepo` sobre o DataStore `munux_settings`. Modela `AppSettings` (provedor de tradução, chave, *base URL*, modelo, idioma de origem/destino). Inclui migração automática da chave do Gemini gravada por versões antigas e *presets* prontos de provedor/modelo.
- **`ReaderPrefs`** — preferências de aparência do leitor (tema, fonte, altura de linha, largura de coluna, filtro *warm*).

### `reader/` — importação e extração

- **`BookImporter`** — copia o arquivo escolhido (via `Uri` do *Storage Access Framework*) para `filesDir/books`, gerando um nome seguro (`UUID` + nome saneado), detecta o formato pela extensão e extrai metadados.
- **`EpubParser`** — abre o EPUB como `ZipFile`, localiza o OPF, lê metadados (`dc:title`, `dc:creator`) e monta a lista de capítulos na ordem da *spine*, além do mapa de recursos internos.
- **`EpubResourceProvider`** — serve os recursos internos do EPUB (imagens, CSS) para o `WebView` do leitor.
- **`PdfTextExtractor`** — usa PDFBox (`PDFTextStripper`) para extrair texto página a página, em `Dispatchers.IO`.

### `translation/` — tradução

Contrato + fábrica desacoplados do provedor:

- **`Translator`** (interface) — `translateHtml` e `translateText`. Erros são sinalizados por `TranslatorException` e subclasses: `RateLimitException` (HTTP 429, com `retryAfterMs`) e `TruncatedException` (resposta cortada por limite de *tokens*, carregando o trecho parcial).
- **`TranslatorFactory`** — instancia a implementação de provedor conforme `AppSettings.provider`. Cada provedor é uma implementação HTTP de `Translator`, selecionável e configurável pelo usuário (URL base, modelo e chave).
- **`TranslationManager`** — orquestra a tradução de um trecho; ao receber `TruncatedException`, divide o conteúdo e retraduz as partes, recompondo o resultado.

### `ui/` — telas Compose

- **`LibraryScreen`** — lista de livros e ação de importar; navega para o leitor ou para as configurações.
- **`ReaderScreen`** — renderiza o capítulo em `WebView` dentro de um `HorizontalPager`, com ponte JS↔Kotlin para o *snap* vertical, troca de capítulo e persistência da posição (`ratio`). Detalhes em [leitor-decisoes.md](leitor-decisoes.md) §1–§2.
- **`SettingsScreen`** — provedor/modelo/chave de tradução, idiomas e aparência do leitor.
- **`theme/Theme.kt`** — temas Claro/Sépia/Escuro mapeados para Material 3.

## Configuração via `local.properties` (bootstrap)

[app/build.gradle.kts](../app/build.gradle.kts) lê chaves de `local.properties` e as expõe como campos de `BuildConfig` (`DEEPSEEK_API_KEY`, `TRANSLATOR_*`, `GEMINI_API_KEY`, etc.). Isso serve apenas para semear a configuração inicial em desenvolvimento — as configurações salvas pelo app prevalecem depois.

`local.properties` é específico da máquina e **não** é versionado (está no `.gitignore`); use [local.properties.example](../local.properties.example) como referência e nunca comite chaves reais.

## Permissões

O app declara apenas `INTERNET` (necessária para a tradução). A importação de livros usa o *Storage Access Framework* (sem permissão de armazenamento ampla); cada livro fica no armazenamento privado do app.
