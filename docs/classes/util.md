# Pacote `util`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md)

---

## Log

Código: [`Log.java`](../../src/main/java/br/pucminas/cd/aco/util/Log.java)

### Responsabilidade
Log mínimo, pensado para a demonstração: toda linha mostra a hora, o id do nó e o relógio de Lamport.

```
16:29:55.339 [N2][L=98] Rodada 1: ENTROU na seção crítica
             │    └── relógio de Lamport no momento do log
             └── id do nó
```

### Métodos
| Método | O que faz |
|---|---|
| `static void init(int id, LongSupplier lamportNow)` | Chamado por `Node` no construtor: define o id e de onde ler o relógio. |
| `static void info(String fmt, Object... args)` | Formata com `String.format` (se houver argumentos) e imprime em `stdout`. |

### Estado
`nodeId` e `lamport` são campos estáticos `volatile` (padrão `-1` e `() -> 0`, por isso logs de testes unitários aparecem como `[N-1][L=0]`).

### Detalhes e decisões
- **Global ao processo:** assume um nó por JVM, como em produção. No `ClusterIntegrationTest`, com três nós na mesma JVM,
  o último `init` vence e todas as linhas saem com o mesmo `[N..]`.
- **Por que não usar uma biblioteca de log?** Para a demo basta `stdout`, que o Docker Compose já agrega por contêiner.
- **Lê o relógio com `now()`, sem incrementar:** logar não é evento do algoritmo de Lamport.
- **Como usar na demo:** ordenar as linhas de todos os nós pelo `L=` aproxima a ordem causal dos eventos,
  mesmo que os relógios físicos das máquinas não estejam sincronizados.
