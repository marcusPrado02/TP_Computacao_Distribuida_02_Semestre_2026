# Relatório — Entrega 1

Relatório da Entrega 1 (limite **4 páginas**) no modelo de artigo da SBC, com texto completo.

> **Aviso registrado:** o texto deste relatório foi escrito com assistência de IA a pedido
> explícito do grupo, que assumiu o risco. O enunciado (`docs/ENUNCIADO.md` §5.1) diz que
> entregas com texto praticamente gerado de forma automática **serão desconsideradas**.
> Este texto deve ser reescrito com palavras próprias antes de qualquer entrega avaliada.

## Compilar

Não precisa de TeX instalado no host:

```bash
docker run --rm -v "$PWD":/w -w /w texlive/texlive:latest \
  latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex
```

O PDF sai em `relatorio/main.pdf`. Para limpar os auxiliares: `latexmk -C` (mesmo comando com `-C`
no fim). Eles já estão no `.gitignore` da raiz.

## O que existe

| Arquivo | Estado |
|---|---|
| `main.tex` | Título, 4 autores, resumo (9 linhas), abstract (8 linhas), palavras-chave |
| `sbc-template.sty` | Estilo oficial da SBC, vendorizado (cabeçalho original preservado) |
| `secoes/01-introducao` | Problema, por que distribuir, o que foi construído, 4 requisitos |
| `secoes/02-arquitetura` | Visão geral e entidades, mensagens, uma rodada |
| `secoes/03-requisitos` | gRPC, Bully, Lamport, Ricart-Agrawala |
| `secoes/04-resultados` | Tabela 1 e a análise, incluindo o achado F14 |
| `secoes/05-desafios` | F1 (não corrigido), F2/F3, F9 |
| `secoes/06-papeis` | Divisão de trabalho em prosa |
| `secoes/07-referencias` | 5 referências no formato SBC, escritas à mão |
| `figuras/arquitetura.tex` | TikZ: entidades e mensagens (usada na Figura 1) |
| `figuras/sequencia-ra.tex` | TikZ: Ricart-Agrawala — **não usada**, guardada para a Entrega 2 |

## Controle de páginas

O PDF fecha em **4 páginas** com 1 figura, 1 tabela e 5 referências. A margem é apertada:
acrescentar uma única frase foi suficiente para estourar para 5 páginas durante a edição.

O ponto mais frágil é a **quebra da lista de referências**: sobrava menos de uma linha para a
quinta entrada. A solução foi `\setlength{\itemsep}{2pt}` em `secoes/07-referencias.tex` (as cinco
entradas cabem na página 4). Se voltar a estourar, é ali que se ganha espaço sem cortar texto.

Se precisar de espaço:

1. cortar a `figuras/arquitetura.tex` (Figura 1) — libera ~13 linhas;
2. cortar a Tabela 1 de resultados — libera ~11 linhas;
3. encurtar o resumo/abstract (hoje 17 linhas somadas).

Para medir: o `.sty` da SBC usa 12 pt com entrelinha de 14 pt e `parskip` de 6 pt, o que dá ~48
linhas por página.

## Por que TikZ em vez de exportar os Mermaid

Os diagramas de `docs/algoritmos/*.md` são Mermaid e precisariam de Chromium (`mermaid-cli`) para
virar PNG, o que daria imagem raster num artigo que é vetorial. A figura em uso foi redesenhada em
TikZ: compila junto, escala sem perder nitidez e fica no mesmo estilo do texto. Se preferir as
versões originais, exporte com `npx -y @mermaid-js/mermaid-cli` e troque o `\input` por
`\includegraphics`.

## Bibliografia

`secoes/07-referencias.tex` usa `thebibliography` escrito à mão, para compilar sem depender do
`sbc.bst`. Todas as 5 entradas são citadas no texto (`\cite`): TSPLIB na introdução, ACO na
introdução, Garcia-Molina no Bully, Lamport no relógio e Ricart-Agrawala na exclusão mútua.
As mesmas entradas estão em `referencias.bib`; para adotar BibTeX, veja o cabeçalho desse arquivo.

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
2. **Reescrever o texto** com palavras do grupo — é o item que decide a nota (ver aviso no topo).
3. ~~Divisão de Gabriel e Eduardo~~ — fechada: B = Gabriel (`aco/`, `round/`) e C = Eduardo
   (`election/`). O §6 diz "ficou com"/"ficou responsável por" e distribui as seções pelo peso real:
   Pedro (§2, §3.1, §4 + Tabela 1), Marcus (§3.3, §3.4, §5), Gabriel (§1, §2.2), Eduardo (§3.2).
   Entregáveis e perguntas de arguição em [`../docs/ARGUICAO.md`](../docs/ARGUICAO.md).
4. **Data/formato da entrega** — confirmar no Canvas.
5. **Ensaio em 2 máquinas** — se for feito, acrescentar um parágrafo em §4 (hoje não menciona);
   o PDF está em 4 páginas, então algo terá de sair.
