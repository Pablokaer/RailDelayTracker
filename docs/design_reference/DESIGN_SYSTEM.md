# IERailMetrics Design System

Este documento é o contrato visual da interface. Foi extraído das referências oficiais em
`docs/design_reference`, usando `overview.png` como base e as telas de Connolly, Heuston,
Journey Planner e Train Map como variações do mesmo produto.

## Princípios

- Produto operacional, preciso e denso, com leitura rápida em monitores de trabalho.
- Dados, origem, escopo e frescor devem permanecer evidentes.
- O verde identifica ação principal, seleção e operação normal; não é decoração.
- Severidade nunca depende apenas de cor: todo estado inclui texto, ícone ou valor.
- Uma única linguagem visual atende dashboards, tabelas, formulários, mapa e drawers.

## Tokens

| Papel | Token CSS | Valor de referência |
|---|---|---|
| Background principal | `--bg-main` | `oklch(0.145 0.018 245)` |
| Sidebar | `--bg-sidebar` | `oklch(0.13 0.025 238)` |
| Card | `--bg-card` | `oklch(0.19 0.022 242)` |
| Card elevado | `--bg-elevated` | `oklch(0.205 0.024 242)` |
| Linha alternada | `--bg-row-alt` | `oklch(0.18 0.022 242)` |
| Borda | `--border` | `oklch(0.31 0.035 241)` |
| Texto principal | `--text-primary` | `oklch(0.95 0.012 235)` |
| Texto secundário | `--text-muted` | `oklch(0.72 0.035 238)` |
| Marca / sucesso | `--ir-green` | `oklch(0.76 0.18 158)` |
| Marca pressionada | `--ir-green-strong` | `oklch(0.69 0.19 158)` |
| Informação | `--blue` | `oklch(0.69 0.14 248)` |
| Alerta | `--amber` | `oklch(0.79 0.16 78)` |
| Erro / atraso grave | `--red` | `oklch(0.68 0.20 24)` |

## Tipografia

Fonte: Inter quando disponível, seguida pela pilha nativa do sistema. Corpo em `14px` a `16px`,
labels compactas nunca menores que `11px`. Títulos de página usam `23px`, peso 700; títulos de
painel `14px` a `16px`, peso 650; métricas `26px` a `30px`, peso 750. Números usam algarismos
tabulares. Texto corrido é limitado a aproximadamente 70 caracteres.

## Espaçamento e forma

Escala base: `4, 6, 8, 12, 16, 20, 24, 32px`. O grid desktop usa gaps de 8 a 12px para painéis
analíticos e 16 a 24px entre seções. Cards usam padding de 14 a 18px, raio de 10 a 12px e borda
de 1px. Sombras são mínimas e reservadas à separação de overlays e drawer. Sidebar fixa com
242px; conteúdo limitado a 1680px.

## Componentes

- **Sidebar:** marca no topo, navegação de 44px, seleção por fundo verde escuro e borda completa,
  status de dados no rodapé. Em telas menores vira drawer com backdrop.
- **Cabeçalho:** título, subtítulo, seletor contextual e indicador de atualização. Altura alvo 88px.
- **Cards de métrica:** ícone, label e valor em leitura horizontal; comparação só quando o dado
  existe. Não inventar tendências.
- **Painéis:** título direto, ações no canto superior direito, conteúdo sem cards aninhados.
- **Inputs:** altura mínima 38px, labels sempre visíveis, fundo escuro, borda clara no hover e foco
  de 2px em verde.
- **Botões:** raio 6 a 8px. Primário verde; secundário neutro; perigo vermelho. Estados hover,
  active, disabled e loading mantêm contraste AA.
- **Tabs e chips:** fundo neutro no repouso, borda e preenchimento verdes na seleção.
- **Badges:** texto explícito e fundo tonal. On time/running verde; arriving/info azul; delayed
  âmbar ou vermelho conforme regra real; cancelled vermelho. Tipos de serviço usam a mesma
  aparência em todas as páginas.
- **Tabelas:** cabeçalho elevado, números tabulares, linhas de 36 a 44px, hover discreto, bordas
  horizontais e scroll horizontal em telas estreitas. Ordenação indica direção em texto/ícone.
- **Gráficos:** Chart.js responsivo, grid e eixos discretos, tooltip escuro, verde como série
  principal e azul como comparação. Severidade usa verde, amarelo, laranja e vermelho.
- **Mapa:** ocupa a superfície dominante. Marcadores combinam cor, símbolo e tooltip. Legenda
  permanece visível no desktop; detalhes aparecem em painel lateral ou sheet em telas estreitas.

## Estados

- **Loading:** skeleton preserva altura e contexto; refresh não apaga o último dado válido.
- **Erro:** mensagem contextual dentro da região, último dado preservado quando possível e ação
  Retry para falhas recuperáveis.
- **Vazio:** explica o filtro/consulta sem resultado e oferece forma de alterá-lo.
- **Hover:** mudança curta de fundo ou borda, nunca deslocamento de layout.
- **Foco:** outline verde de 2px com offset de 2px.
- **Selecionado:** fundo tonal, borda completa e texto de alto contraste.
- **Transições:** 150 a 220ms, `cubic-bezier(.16,1,.3,1)`. Respeitar `prefers-reduced-motion`.

## Layout responsivo

- **Desktop, >= 1200px:** sidebar fixa; grids analíticos completos; mapa com drawer lateral.
- **Tablet, 768 a 1199px:** sidebar em drawer; métricas em 2 ou 3 colunas; gráficos principais em
  uma coluna quando necessário; tabelas roláveis.
- **Mobile, < 768px:** conteúdo em uma coluna; métricas em 2 colunas (1 coluna abaixo de 420px);
  controles empilhados; timeline vertical; mapa com detalhes abaixo ou em painel de tela cheia.
- Alvos interativos mantêm pelo menos 40px e nenhum input reduz abaixo de largura útil.

## Uso do logo

O original permanece em `docs/design_reference/logo_wolfhound.png`. A cópia de produção fica em
`src/main/resources/static/images/logo_wolfhound.png`, sem filtros, distorção ou alteração de
proporção. O mesmo asset é usado como favicon.
