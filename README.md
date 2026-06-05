# Munux Books

Leitor de **EPUB** e **PDF** para Android, com foco em leitura confortável (tipografia, temas, paginação por encaixe) e **tradução automática integrada** de capítulos para o seu idioma.

![Plataforma](https://img.shields.io/badge/plataforma-Android-3ddc84)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7f52ff)
![Licença](https://img.shields.io/badge/licen%C3%A7a-GPL--2.0-green)

> Status: em desenvolvimento (`versionName 0.1.0`).

---

## Recursos

- **Biblioteca local** — importe arquivos `.epub` e `.pdf` do dispositivo; cada livro é copiado para o armazenamento privado do app.
- **Leitor de EPUB** — renderização do HTML do capítulo em `WebView`, com recursos internos (imagens, CSS) servidos diretamente do pacote.
- **Leitor de PDF** — extração de texto página a página.
- **Paginação por rolagem vertical com *snap*** — a rolagem livre encaixa automaticamente na próxima altura de tela, dando passos discretos em vez de movimento contínuo.
- **Memória de posição** — a posição de leitura é salva por `(capítulo, proporção)` e sobrevive a mudanças de fonte, tema e rotação.
- **Três temas** — Claro, Sépia e Escuro, com filtro *warm* noturno opcional.
- **Tipografia ajustável** — tamanho de fonte, altura de linha e largura de coluna por *sliders*.
- **Presets de um toque** — *Manhã* e *Noite* ajustam tema, fonte e brilho de uma vez.
- **Tradução automática integrada (opcional)** — traduz o conteúdo do livro entre idiomas usando o provedor que **você** escolher e a **sua própria** chave de API. O app funciona como leitor mesmo sem nenhuma chave (veja [Configurar tradução](#configurar-tradução)).

As decisões de UX/tipografia/tema do leitor estão documentadas em [docs/leitor-decisoes.md](docs/leitor-decisoes.md). A visão de arquitetura está em [docs/arquitetura.md](docs/arquitetura.md).

---

## Requisitos

| Ferramenta | Versão |
| --- | --- |
| Android SDK | `compileSdk` / `targetSdk` 35, `minSdk` 26 (Android 8.0+) |
| JDK | 17 |
| Gradle | 8.9 (via *wrapper*) |
| Kotlin / Compose | conforme `gradle/libs.versions.toml` |

---

## Como compilar

```bash
# 1. Clone o repositório
git clone <url-do-repositório>
cd Munux-books

# 2. Crie o seu local.properties a partir do template
cp local.properties.example local.properties
# edite local.properties e ajuste sdk.dir para o caminho do seu Android SDK

# 3. Compile e instale a versão debug em um dispositivo/emulador conectado
./gradlew installDebug

# ou apenas gere o APK
./gradlew assembleDebug   # saída em app/build/outputs/apk/debug/
```

> `local.properties` é específico da sua máquina (caminho do SDK e chaves) e **não** é versionado — está no `.gitignore`. Use sempre o `local.properties.example` como referência.

---

## Configurar tradução

A tradução é **opcional** e usa a chave de API que **você** trouxer — o app não vem com nenhum provedor ou chave embutidos. Sem configurar nada, ele funciona como leitor normalmente; a tradução só liga quando você escolhe um provedor e informa a sua chave.

Há duas formas de configurar:

1. **Dentro do app** (recomendado) — abra **Configurações**, escolha o provedor e o modelo que quiser e cole a **sua** chave. Existem *presets* prontos para vários provedores. As configurações salvas no app sempre prevalecem.
2. **Via `local.properties`** (apenas bootstrap de desenvolvimento) — semeia a configuração inicial enquanto não houver chave salva pelo app. Veja os exemplos comentados em [local.properties.example](local.properties.example). Esses valores são lidos por [app/build.gradle.kts](app/build.gradle.kts) e expostos via `BuildConfig`.

> **Importante:** `local.properties` é ignorado pelo git de propósito — **nunca** coloque chaves reais em arquivos versionados. Se você for distribuir um **APK pronto** (não o código), gere-o com `local.properties` **sem** chaves, para não embutir a sua chave no binário.

### Provedores suportados

| Provedor | Tipo de endpoint | Exemplo de modelo |
| --- | --- | --- |
| Gemini | API nativa do Google | `gemini-2.5-flash` |
| DeepSeek / OpenAI / Groq / OpenRouter / Mistral / Together / Ollama / vLLM | OpenAI-compatível (`base URL` configurável) | `deepseek-chat`, `gpt-4o-mini`, … |
| Anthropic | API nativa Messages | `claude-haiku-4-5` |

O app trata limites de taxa (HTTP 429, respeitando o `retry-after`) e respostas truncadas por limite de *tokens*, dividindo o trecho e tentando novamente.

---

## Estrutura do projeto

```
app/src/main/java/com/munux/books/
├── MainActivity.kt          # NavHost: biblioteca → leitor → configurações
├── MunuxBooksApp.kt         # Application
├── data/                    # Room (livros) + DataStore (configurações/preferências)
├── reader/                  # Importação, parser de EPUB, extração de PDF
├── translation/             # Contrato Translator + implementações por provedor
└── ui/                      # Telas Compose (Library, Reader, Settings) + tema
```

Detalhes em [docs/arquitetura.md](docs/arquitetura.md).

---

## Uso

O Munux Books é uma ferramenta de **leitura e tradução**. Ele não distribui nem hospeda livros: o usuário importa os próprios arquivos no aparelho e usa a própria chave de API para traduzi-los. A responsabilidade pelo conteúdo importado e pelo uso da API é de quem utiliza o app.

## Licença

Distribuído sob a **GNU General Public License v2.0**. Veja [LICENSE](LICENSE).
