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
| `main.tex` | Título, autores (2 de 4+), resumo/abstract como marcador, `\input` das seções |
| `sbc-template.sty` | Estilo oficial da SBC, vendorizado (cabeçalho original preservado) |
| `secoes/01` a `03` | Títulos, tabela de mensagens e figuras prontos; **prosa pendente** |
| `secoes/04-resultados` | Tabela com números medidos; falta o parágrafo de análise |
| `secoes/05-desafios` | 4 tópicos da auditoria como roteiro (escritos como lembrete, não como texto) |
| `secoes/06-papeis` | Tabela pronta, nomes pendentes |
| `secoes/07-referencias` | 6 referências no formato SBC, escritas à mão |
| `figuras/*.tex` | TikZ: arquitetura e sequência do Ricart-Agrawala (vetorial, sem PNG) |

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

1. **Autores** — `TODO.md` ainda tem `A = ____`; hoje o `git shortlog` mostra 2 pessoas com commits.
2. **Data/formato da entrega** — confirmar no Canvas.
3. **Texto** — uma seção por pessoa, conforme a divisão do `TODO.md`.
4. **Ensaio em 2 máquinas** — confirmar os números da Tabela 2 e citar na §4.
5. **Remover os marcadores `\pendente`** e conferir o limite de 4 páginas.
