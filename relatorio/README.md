# Relatório — andaime

Estrutura do relatório da Entrega 1 (limite **4 páginas**) no modelo de artigo da SBC.
O texto de cada seção é escrito pelo grupo: o enunciado desconsidera texto gerado
automaticamente, então aqui só existem **estrutura, figuras, tabela e referências**.

## Compilar

Não precisa de TeX instalado no host:

```bash
docker run --rm -v "$PWD":/w -w /w texlive/texlive:latest \
  latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex
```

O PDF sai em `relatorio/main.pdf`. Para limpar os auxiliares: `latexmk -C` (mesmo comando com `-C`
no fim). Eles já estão no `.gitignore` da raiz.

## O que está pronto e o que falta

| Arquivo | Estado |
|---|---|
| `main.tex` | Título, 4 autores, resumo/abstract e palavras-chave com roteiro completo (8 linhas cada) |
| `sbc-template.sty` | Estilo oficial da SBC, vendorizado (cabeçalho original preservado) |
| `secoes/01` a `06` | Tabelas/figuras prontos **e roteiro completo parágrafo por parágrafo**; prosa pendente |
| `secoes/07-referencias` | 6 referências no formato SBC, escritas à mão |
| `figuras/*.tex` | TikZ: arquitetura e sequência do Ricart-Agrawala (vetorial, sem PNG) |

### Como usar o roteiro completo de cada seção

Cada arquivo de `secoes/` abre com um bloco marcado `% ===== ROTEIRO COMPLETO =====`.
Ele cobre **todos os parágrafos** da seção, e cada parágrafo tem um bloco `[P1]`, `[P2]`...
com: o que aquele parágrafo afirma, os fatos na ordem em que devem aparecer, os números
exatos, o `arquivo:linha` de onde vieram e, às vezes, uma "frase-alvo" com o argumento.

Nada fica por inventar: se um fato não está no roteiro, ele não deve entrar no texto.
O roteiro **não** sai no PDF — é um `%` por linha.

Fluxo por pessoa:

1. abrir o arquivo da sua seção e ler o bloco;
2. escrever **um parágrafo por bloco `[P*]`**, em prosa, seguindo a ordem dos fatos;
3. apagar o bloco `% ROTEIRO` e os `\pendente{...}` correspondentes;
4. compilar e conferir o número de páginas.

Se um fato do roteiro estiver errado, corrija **no texto**, não no roteiro.

### Orçamento de páginas (medido, não estimado)

Compilando com os marcadores desligados, o andaime (títulos, Figura 1, Tabelas 1–3 e
referências) ocupa **~2,55 páginas de 4** — sobram **~1,45 página**, ou ~70 linhas.

O resumo e o abstract vão consumir ~16 linhas desse total, então o texto das cinco seções
deve caber em **~55 linhas**. Os orçamentos nos roteiros somam ~57:

| Seção | Linhas |
|---|---|
| 1. Introdução | 10 |
| 2. Arquitetura | 13 (+ Figura 1 e Tabela 1) |
| 3. Requisitos | 21 (+ Figura 2) |
| 4. Resultados | 7 (+ Tabela 2) |
| 5. Desafios | 8 |
| 6. Papéis | tabela, sem prosa |

**Se estourar:** a primeira coisa a cortar é a **Figura 2** (sequência do Ricart-Agrawala),
que libera ~18 linhas; depois a Tabela 1. A ordem de prioridade é requisitos > resultados >
arquitetura > introdução.

Para medir de novo: troque `\newif\ifpendentes \pendentestrue` por `\pendentesfalse` no
`main.tex` (hoje: 4 páginas com marcadores, 3 sem).

Os marcadores vermelhos `[PENDENTE: ...]` aparecem no PDF de propósito: são o checklist visual do
que ainda falta escrever. **Remova todos antes de entregar** (`grep -rn pendente secoes/`).

## Por que TikZ em vez de exportar os Mermaid

Os diagramas de `docs/algoritmos/*.md` são Mermaid e precisariam de Chromium (`mermaid-cli`) para
virar PNG, o que daria imagem raster num artigo que é vetorial. As duas figuras aqui foram
redesenhadas em TikZ: compilam junto, escalam sem perder nitidez e ficam no mesmo estilo do texto.
Se preferir as versões originais, exporte com `npx -y @mermaid-js/mermaid-cli` e troque o
`\input` por `\includegraphics`.

## Bibliografia

`secoes/07-referencias.tex` usa `thebibliography` escrito à mão, para compilar sem depender do
`sbc.bst`. As mesmas 6 entradas estão em `referencias.bib`; para adotar BibTeX, veja o cabeçalho
desse arquivo.

## Template oficial

- SBC → Publicações → **Modelos para Publicações**: pacote `sbc-template.zip`
  (`sbc-template.sty`, `sbc.bst`, `sbc-template.tex`).
- No Overleaf, procure *"Sociedade Brasileira de Computação (SBC)"* — é o mesmo meta-artigo, melhor
  opção para 4–5 pessoas editando ao mesmo tempo.
- Regras do estilo que costumam pegar: **resumo/abstract com no máximo 10 linhas, na primeira
  página**; palavras-chave logo abaixo do abstract.

Se a SBC publicar uma versão nova do `.sty`, substitua o arquivo desta pasta pelo oficial.

## Pendências (ver `../PENDENCIAS.md` §3)

1. ~~Autores~~ — fechado: Pedro Henrique Vilaça Valverde, Marcus Prado Silva,
   Gabriel Batista de Almeida e Eduardo Alves Salgado Lisboa Oliveira.
2. **Módulo de Gabriel e Eduardo** — a Tabela 3 só tem o que o git comprova.
3. **Data/formato da entrega** — confirmar no Canvas.
4. **Texto** — uma seção por pessoa, a partir do roteiro de cada arquivo.
5. **Ensaio em 2 máquinas** — confirmar os números da Tabela 2 e citar na §4.
6. **Remover os marcadores `\pendente` e os roteiros** e conferir o limite de 4 páginas.
