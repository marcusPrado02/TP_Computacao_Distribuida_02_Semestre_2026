Instâncias TSPLIB. Aponte `TSP_FILE=/app/data/<arquivo>` (Docker: `data/` é copiado para a imagem)
ou `./tp up -f data/<arquivo>` (local).

| Arquivo | Cidades | Tipo | Ótimo conhecido | Origem |
|---|---|---|---|---|
| `berlin52.tsp` | 52 | `EUC_2D` | 7542 | TSPLIB95 (Reinelt, Univ. Heidelberg); cópia de `github.com/mastqe/tsplib` |

Ensaio de 01/11/2026 (4 nós, 40 formigas, 20–30 rodadas): o cluster chega a **7542**, o ótimo.
