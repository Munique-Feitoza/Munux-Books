# Decisões de design do leitor

Este documento registra **cada decisão de UX/UI/tipografia/tema do leitor** do Munux Books com a sua justificativa.

## Como ler

Cada decisão tem uma etiqueta de **força da evidência**:

| Etiqueta | Significado |
| --- | --- |
| 🔬 **Evidência** | Estudo peer-reviewed ou publicação técnica de instituição reconhecida (universidade, NIH, VESA, etc.). |
| 🏭 **Convenção comercial** | Kindle, Apple Books e/ou Google Play Books fazem assim há anos sem refutação relevante. Não é estudo, mas é prática estabelecida da indústria — esses fabricantes investem em pesquisa interna não publicada. |
| 🤔 **Hipótese pragmática** | Decisão por intuição/coerência/UX, sem evidência forte. Marcamos pra revisar se aparecer estudo contrário. |

Quando uma decisão combina mais de uma categoria, listamos todas.

---

## 1. Paginação por rolagem vertical com snap por altura de viewport

**Decisão.** O conteúdo de cada capítulo é renderizado em **fluxo vertical normal** dentro do WebView. A usuária rola pra baixo continuamente; ao parar de rolar, o body faz **snap automático** pra próxima altura múltipla de `innerHeight` (uma "página"). Trocar de capítulo é por **swipe vertical extra** no fim/início do capítulo, capturado pela ponte JS↔Kotlin (`onCrossChapter`).

**Como chegamos aqui.** A primeira implementação usava paginação horizontal com CSS multi-column (`column-width: 100vw`). Convergia com Kindle/Apple Books/Google Play Books como referência, mas **o WebView do Android (Chromium embarcado) tem bug de layout específico** com conteúdo de EPUB: o primeiro elemento do `#munux-pages` era posicionado em `x = innerWidth` (coluna 2) em vez de `x = 0` (coluna 1), deixando a primeira página visível sempre em branco. Diagnosticado via `console.log` do `getBoundingClientRect()` em 2026-05-19 — `MUNUX first tag=P rect x=431 y=0 w=371 h=24` com `innerWidth=411`. Várias tentativas de mitigar (resetar `page-break-*`, `column-fill`, `min-height` em containers, etc.) não resolveram em todos os capítulos.

**Justificativa da troca para vertical.**

- 🛠 **Robustez primeiro.** Vertical scroll é fluxo normal de HTML — não depende de nenhum recurso de layout exótico nem de bugs específicos do engine. Funciona em qualquer WebView, em qualquer ROM, em qualquer tamanho de tela.
- 🏭 **Convenção comercial.** Kindle (todos os apps e dispositivos), Apple Books e Google Play Books oferecem paginação horizontal **e** scroll vertical como modos. Apple Books e Google Play Books expõem ambos no menu de aparência ([Apple Books layout options](https://support.apple.com/guide/books/change-the-appearance-of-books-bka38bb6f7f7/mac), [Google Play Books reading display](https://support.google.com/googleplay/answer/2825196?hl=en)). Vertical é uma opção legítima, só não era a padrão deles.
- 🤔 **Cinetose:** o snap por altura de viewport (não scroll livre infinito) aproxima o comportamento da paginação discreta — input visual chega em "passos" suaves quando você solta o dedo. Não há estudo controlado comparando os dois e cinetose; medimos empiricamente.
- 🏭 **Responsividade automática.** Fluxo vertical re-pagina sozinho em qualquer rotação/tamanho/fonte. Quando muda a viewport, restauramos pela `ratio = scrollY / (scrollHeight - innerHeight)` salva no banco — sobrevive a mudança de fonte/tema/rotação.

**Trade-off conhecido.** Sem o "swipe pra direita = próxima página" do livro físico, perdemos um pouco da metáfora de leitura tradicional. Mitigamos com snap automático: a usuária rola, solta, e a página encaixa — não fica "no meio" de duas páginas.

**Como funciona internamente (resumo).**

1. Cada capítulo vai num WebView dentro de um `HorizontalPager` (com `userScrollEnabled = false` — o pager é trocado só por ponte JS).
2. O HTML do capítulo é injetado em `<div id="munux-pages">` com `body { overflow-y: auto }`.
3. O JS captura `scroll` no `window`, faz debounce de 220ms, e chama `scrollTo({top: page * innerHeight, behavior: 'smooth'})` pra alinhar na página mais próxima.
4. No fim do capítulo (`scrollY >= maxScroll`) + swipe pra cima → chama `MunuxBridge.onCrossChapter(1)`, Kotlin avança o `HorizontalPager`. Análogo pra capítulo anterior.

---

## 2. Memória de posição por `(capítulo, ratio)`

**Decisão.** A cada parada de scroll (debounce de 220ms via JS), o WebView reporta `ratio = scrollY / (scrollHeight - innerHeight)` (0–1) pra ponte Kotlin, que salva em `books.currentScrollRatio` (REAL no SQLite). Capítulo atual fica em `books.currentPage`. Ao reabrir o livro, restauramos pra `scrollTo({top: ratio * maxScroll})` dentro do capítulo certo.

**Justificativa.**

- 🏭 **Convenção comercial universal.** Kindle (Whispersync), Apple Books (iCloud sync) e Google Play Books salvam posição exata de leitura — não é nem questionado. Whispersync é descrito pela Amazon como salvar "the last page read, bookmarks, notes, and highlights". ([Kindle Whispersync](https://www.amazon.com/gp/help/customer/display.html?nodeId=GFKDDM7PZJB9SKWG))
- 🛠 **Por que ratio e não offset absoluto:** quando a usuária muda fonte/tema/margem, o `scrollHeight` muda. Um índice absoluto (página 5/58) ficaria inconsistente. Ratio 0–1 sobrevive a essas mudanças e recalcula sozinho na restauração.
- 🤔 **Decisão local-only nesta versão.** Não fazemos sync entre dispositivos por enquanto — o app é single-device. Sync via Google Drive/Dropbox fica em backlog.

**Trade-off conhecido.** Ratio é menos preciso que offset absoluto pra "voltar exatamente naquela frase" — pode ser que você abra umas linhas antes ou depois do exato ponto onde parou. Em leitura corrida é imperceptível; pra anotações/bookmarks (backlog) usaríamos referência por âncora HTML (`id` do parágrafo) em vez de ratio.

---

## 3. Três temas: Claro, Sépia, Escuro (+ opcional Warm filter)

**Decisão.** Oferecer três temas selecionáveis: **Claro** (fundo branco/quase-branco, texto preto-azulado), **Sépia** (fundo creme, texto marrom-escuro), **Escuro** (fundo cinza-escuro, texto cinza-claro). Adicional: toggle "Filtro warm noturno" que aplica um overlay levemente amarelado sobre qualquer tema.

**Justificativa.**

- 🔬 **Evidência (ambiente importa mais que o tema):** estudo de 2025 no PMC com tablet ([Immediate Effects of Light/Dark Mode on Visual Fatigue in Tablet Users](https://pmc.ncbi.nlm.nih.gov/articles/PMC12027292/)) e revisão da Ergonomics 2025 ([The dark side of the interface](https://www.tandfonline.com/doi/full/10.1080/00140139.2025.2483451)) concluem que **modo claro tende a ser melhor em ambiente bem iluminado**, **modo escuro melhor em pouca luz**. O efeito é fortemente moderado pela iluminação ambiente, idade e acuidade. A American Academy of Ophthalmology mantém que **fadiga visual digital vem mais do comportamento (piscar menos diante de telas) do que do espectro do display em si**. ([AAO – Computers, Digital Devices and Eye Strain](https://www.aao.org/eye-health/tips-prevention/computer-usage))
- 🏭 **Convenção comercial:** Kindle tem 4 temas (Branco, Sépia, Verde, Preto). Apple Books tem 4 (Branco, Sépia, Cinza, Preto). Google Play Books tem 3 (Branco, Sépia, Preto). Convergência forte → 3 temas é o mínimo aceitável; 4 seria mais flexível, mas começamos com 3 pra simplicidade.
- 🤔 **Hipótese (warm filter no escuro):** muitos usuários relatam preferência subjetiva por warm filter à noite. Mantemos como opção, **sem prometer benefício de sono** (ver Decisão 4).

**Trade-off conhecido.** Tema escuro com texto branco gritante em fundo preto puro pode gerar borrão em scroll em telas OLED (ver Decisão 5). Mitigamos com cinza-claro sobre cinza-escuro, não branco-puro sobre preto-puro.

---

## 4. Warm filter (filtro luz azul) é opcional, sem promessa de melhorar sono

**Decisão.** O filtro warm/âmbar existe como **opção de conforto visual subjetivo**. A descrição na UI **não** afirma que melhora o sono.

**Justificativa.**

- 🔬 **Evidência contra benefício forte para sono:**
  - [Blue light filter applications and sleep quality (Tandfonline, 2024)](https://www.tandfonline.com/doi/abs/10.1080/15368378.2024.2327432) — estudo observacional com usuários de smartphone: **sem relação estatisticamente significativa** (p = 0,925) entre uso de filtro de luz azul e qualidade do sono.
  - [Blue-light blocking glasses meta-analysis (Frontiers Neurology, 2025)](https://www.frontiersin.org/journals/neurology/articles/10.3389/fneur.2025.1699303/full) — meta-análise: evidência **inconsistente**, amostras pequenas, protocolos heterogêneos.
  - [Estudo PLOS One 2025](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0332877) — óculos blue-light-blocking adiantaram fase de sono mas **não alteraram melatonina salivar**.
- 🏭 **Convenção comercial:** Kindle ("Warmth"), Apple Books ("True Tone"/"Night Shift"), Google Play Books ("Schedule") — todos oferecem warm filter mas a Apple, no caso do Night Shift, descreve como "may help you get a better night's sleep" ("pode ajudar") com cautela. ([Apple sobre Night Shift](https://support.apple.com/en-us/108078))

---

## 5. Cinza-escuro em vez de preto puro no tema escuro

**Decisão.** Tema escuro usa um cinza-escuro (na faixa `#15–22` para o fundo) com texto cinza-claro (na faixa `#d0–e0`), **não** preto puro `#000` com branco puro `#fff`.

**Justificativa.**

- 🔬 **Evidência sobre persistência sample-and-hold em OLED:** texto branco gritante em fundo preto durante scroll/movimento de tela apresenta borrão perceptível em painéis OLED de smartphone, por causa de **persistência sample-and-hold** (cada frame fica visível ~4,17 ms a 240 Hz), não por "smearing" químico do OLED. ([Blur Busters – OLED Motion Blur FAQ](https://blurbusters.com/faq/oled-motion-blur/), [Display motion blur (Wikipedia + refs VESA)](https://en.wikipedia.org/wiki/Display_motion_blur))
- 🤔 **Mitigação correta vs incorreta:** a mitigação técnica adequada para o blur é **maior taxa de atualização** ou Black Frame Insertion, não trocar a cor de fundo. No entanto, **reduzir o contraste extremo (preto puro/branco puro → cinza-escuro/cinza-claro) reduz a percepção do efeito**, mesmo que não elimine a causa. Isso é hipótese pragmática.
- 🏭 **Convenção comercial:** Kindle modo "Preto" usa preto profundo mas o texto não é branco-puro absoluto; Apple Books idem. Material Design da Google **recomenda explicitamente fundo `#121212`** em modo escuro, não `#000`, pra reduzir contraste extremo e fadiga em sessões longas. ([Material Design – Dark theme](https://m2.material.io/design/color/dark-theme.html))

---

## 6. Tamanho de fonte, altura de linha e largura de coluna

**Decisão (defaults).** Tamanho de fonte: 17sp. Altura de linha (line-height): 1.55. Largura útil de coluna: tenta caber **55–75 caracteres por linha** (`max-width: 38em` no body com margens auto). Todos configuráveis via sliders.

**Justificativa.**

- 🏭 **Convenção comercial:** as recomendações repetidas em todas as guidelines de tipografia digital convergem em:
  - corpo ≥ 16 px;
  - line-height 1.4–1.6 em corpos longos;
  - 45–75 caracteres por linha.
  - Trabalho fundacional do Nielsen Norman Group desde 2006, mantido nas atualizações até 2019. ([NN/g – Legibility, readability, and comprehension](https://www.nngroup.com/articles/legibility-readability-comprehension/))
  - Reforçado pelo Lighthouse/PageSpeed do Google, que sinaliza texto < 16 px como problema de acessibilidade.
- 🏭 **Defaults dos leitores comerciais:** Kindle, Apple Books e Google Play Books abrem livros com defaults na faixa 17–19 pt, line-height ~1.5, margens generosas. Nossos defaults estão dentro dessa faixa.

**Trade-off conhecido.** Sem estudo recente comparando serifa vs sem-serifa em DPI alto de smartphone. Vamos usar **a fonte do CSS do próprio EPUB** quando ele especificar uma; caso contrário, padrão do sistema (Roboto no Android). É a abordagem do Apple Books e Google Play Books.

---

## 7. Transições de página: snap suave nativo do browser

**Decisão.** Mudança de página é via `scrollTo({ behavior: 'smooth' })` — usa o smooth scroll nativo do Chromium, que é uma transição curta (~200ms) sem fade nem slide custom. O browser respeita `prefers-reduced-motion` do sistema automaticamente.

**Justificativa.**

- 🏭 **Convenção comercial:** o "page curl" do Apple Books era padrão em iOS antigos e foi **descontinuado em iOS 16** em favor de slide simples ou snap, justamente para reduzir motion sickness e custo de CPU. Kindle moderno usa snap sem efeito de virar página por padrão. Google Play Books também.
- 🔬 **Acessibilidade web:** `prefers-reduced-motion` é uma media query CSS criada justamente porque **alguns usuários têm desconforto vestibular com animações** — Google/Apple/Microsoft suportam. ([web.dev – prefers-reduced-motion](https://web.dev/articles/prefers-reduced-motion))
- 🤔 **Sem evidência específica** de que snap vs slide afete cinetose em leitura. Decisão pragmática + alinhamento com acessibilidade.

---

## 8. Cinetose em leitura no transporte

**Decisão.** Não prometemos "este app cura enjoo de leitura". O que fazemos:
1. Paginação por snap vertical (Decisão 1) — a rolagem livre é seguida de encaixe automático na próxima altura de viewport, dando "passos" discretos em vez de movimento contínuo.
2. Snap sem animação fancy (Decisão 7) — minimiza movimento artificial na tela.
3. Letra grande por padrão (Decisão 6) — reduz saccades visuais, em tese facilitando recuperação rápida.

Adicionamos uma **dica visível no app** com a única intervenção que tem suporte estabelecido:

> 💡 Em ônibus/carro: olhe pela janela por 20–30 segundos a cada 5 minutos de leitura. Isso re-sincroniza o sistema vestibular e reduz a chance de enjoo.

**Justificativa.**

- 🔬 **Evidência de que VIMS em transporte é real:** [Visually induced motion sickness correlates with on-road car sickness (Exp Brain Res, 2025)](https://link.springer.com/article/10.1007/s00221-025-07020-z) — TU Delft, n=22, mostrou correlação entre susceptibilidade a cinetose em VR e enjoo real lendo em carro.
- 🔬 **Evidência sobre referencial visual externo:** o trabalho clássico de [Reason & Brand, "Motion Sickness" (1975)](https://psycnet.apa.org/record/1975-31049-000) estabeleceu o modelo de conflito sensorial; intervenções subsequentes que aumentam **referência visual ao mundo real estável** (horizonte, janela) são consistentemente reportadas como mitigantes.
- 🔬 **Evidência sobre parâmetros de display em veículos:** [Modulation of In-Vehicle Display Parameters (MDPI Electronics, 2025)](https://www.mdpi.com/2079-9292/14/11/2249) — modular parâmetros do display reduziu cinetose em até 40% em ambiente controlado, mas o estudo é veicular automotivo, não smartphone em ônibus.
- 🤔 **Os efeitos específicos de tamanho de fonte/scroll vs swipe/brilho como mitigantes de enjoo em smartphone em transporte público não foram medidos** em estudo controlado que tenhamos encontrado.

---

## 9. Presets "Manhã" e "Noite"

**Decisão.** Dois botões de um toque na tela de Aparência:
- **🌅 Manhã (ônibus, sol)**: tema Sépia, fonte 18sp, line-height 1.55, brilho do app medindo claro (sem dim), warm filter desligado.
- **🌙 Noite (ônibus voltando, luz baixa)**: tema Escuro, fonte 19sp, line-height 1.6, dim/brilho do app reduzido, warm filter ligado.

**Justificativa.**

- 🔬 **Componente de evidência:** ambiente claro → tema claro/sépia; ambiente escuro → tema escuro (Decisão 3).
- 🏭 **Convenção comercial:** Kindle tem "scheduled themes" baseado em horário; Apple Books integra com "Auto-Brightness" e "Night Shift" automáticos; Google Play Books tem "Auto" theme switch.
- 🤔 **Os valores específicos** (Sépia + 18sp + line-height 1.55 etc.) são razoáveis dentro das convenções, mas o ponto exato não é peer-reviewed. Você ajusta com slider se quiser.

---

## 10. Cores específicas usadas

**Decisão (referência aproximada, ajustável).**

| Tema | Fundo | Texto | Links |
| --- | --- | --- | --- |
| Claro | `#fdfdfb` | `#202225` | `#1565c0` |
| Sépia | `#f4ecd8` | `#3b2f1c` | `#7c4a00` |
| Escuro | `#1a1d24` | `#d8d4cc` | `#90caf9` |

**Justificativa.**

- 🏭 **Convenção comercial:** valores escolhidos para ficarem **dentro da faixa visualmente similar à dos modos correspondentes do Kindle e Apple Books**, mas ajustados para o Material Design (que recomenda `#121212`–`#1f1f1f` como base de modo escuro em vez de preto puro — ver [Material Design – Dark theme](https://m2.material.io/design/color/dark-theme.html)).
- 🤔 **Nenhum estudo** valida estes hex codes em particular como ótimos. Se o teste em campo mostrar incômodo, ajustamos.

---

## Resumo das referências externas

### Cinetose em transporte

1. Hessel, M. et al. (2025). *Visually induced motion sickness correlates with on-road car sickness.* Experimental Brain Research. https://link.springer.com/article/10.1007/s00221-025-07020-z
2. (2025). *Modulation of In-Vehicle Display Parameters.* MDPI Electronics. https://www.mdpi.com/2079-9292/14/11/2249
3. Reason, J. T., & Brand, J. J. (1975). *Motion Sickness.* Academic Press. https://psycnet.apa.org/record/1975-31049-000

### Tema claro vs escuro

4. (2025). *Immediate Effects of Light/Dark Mode on Visual Fatigue in Tablet Users.* PMC. https://pmc.ncbi.nlm.nih.gov/articles/PMC12027292/
5. (2025). *The dark side of the interface.* Ergonomics. https://www.tandfonline.com/doi/full/10.1080/00140139.2025.2483451
6. American Academy of Ophthalmology. *Computers, Digital Devices and Eye Strain.* https://www.aao.org/eye-health/tips-prevention/computer-usage
7. Google. *Material Design – Dark theme.* https://m2.material.io/design/color/dark-theme.html

### Filtro de luz azul e sono

8. (2024). *Blue light filter applications and sleep quality.* Electromagnetic Biology and Medicine. https://www.tandfonline.com/doi/abs/10.1080/15368378.2024.2327432
9. (2025). *Blue-light blocking glasses meta-analysis.* Frontiers in Neurology. https://www.frontiersin.org/journals/neurology/articles/10.3389/fneur.2025.1699303/full
10. (2025). *PLOS ONE – Blue light blocking glasses and sleep phase / melatonin.* https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0332877

### Tecnologia de display

11. Blur Busters. *Why Do Some OLEDs Have Motion Blur?* https://blurbusters.com/faq/oled-motion-blur/
12. Wikipedia (com referências VESA). *Display motion blur.* https://en.wikipedia.org/wiki/Display_motion_blur

### Tipografia digital

13. Nielsen Norman Group. *Legibility, Readability, and Comprehension.* https://www.nngroup.com/articles/legibility-readability-comprehension/
14. web.dev (Google). *prefers-reduced-motion.* https://web.dev/articles/prefers-reduced-motion

### Convenções dos leitores comerciais

15. Apple Support. *Change the appearance of books in Books on Mac.* https://support.apple.com/guide/books/change-the-appearance-of-books-bka38bb6f7f7/mac
16. Apple Support. *Use Night Shift.* https://support.apple.com/en-us/108078
17. Google Play Books Help. *Customize your reading display.* https://support.google.com/googleplay/answer/2825196
18. Amazon. *Kindle Whispersync.* https://www.amazon.com/gp/help/customer/display.html?nodeId=GFKDDM7PZJB9SKWG

---

## Revisão deste documento

Atualizar este documento quando:

- Aparecer estudo novo relevante que **confirme**, **refute** ou **contradiga** uma decisão marcada 🤔 ou 🏭.
- O Munux Books mudar uma decisão. (Manter o histórico do "porquê" da mudança.)
- A pessoa usuária reportar desconforto ou dificuldade num cenário não previsto.

Última revisão: 2026-05-19 — §1 e §2 reescritos: trocamos paginação horizontal (CSS multi-column) por rolagem vertical com snap por viewport, depois de diagnosticar bug do Chromium do WebView que deixava a primeira coluna em branco. §7 ajustado pra refletir o uso de smooth scroll nativo.
