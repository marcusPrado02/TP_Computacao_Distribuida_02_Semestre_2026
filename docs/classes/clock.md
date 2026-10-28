# Pacote `clock`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md) · Algoritmo: [`../algoritmos/lamport.md`](../algoritmos/lamport.md)

---

## LamportClock

Código: [`LamportClock.java`](../../src/main/java/br/pucminas/cd/aco/clock/LamportClock.java)

### Responsabilidade
Relógio lógico de Lamport do nó: um contador que só cresce e que garante
"se o evento *a* causou o evento *b*, então `L(a) < L(b)`".
É usado para ordenar os pedidos de seção crítica (Ricart-Agrawala) e para carimbar os logs (`[L=...]`).

### Quem cria / quem usa
- Criado por `Node` (um por nó).
- `PeerNetwork.header()` chama `tick()` em **todo envio** e `onReceive()` ao receber **toda resposta**.
- `NodeServiceImpl.receive()` chama `onReceive()` ao receber **toda requisição**.
- `RicartAgrawala.acquire()` chama `tick()` para gerar o timestamp do pedido.
- `Log` lê `now()` para imprimir o valor atual.

### Estado
| Campo | Significado |
|---|---|
| `long time` | Valor atual do relógio; começa em 0. |

### Métodos
| Método | Regra | Retorno |
|---|---|---|
| `long tick()` | Evento local ou envio: `time = time + 1` | novo valor |
| `long onReceive(long remote)` | Recebimento: `time = max(time, remote) + 1` | novo valor |
| `long now()` | Leitura, sem alterar | valor atual |

### Thread-safety
Os três métodos são `synchronized`. Várias threads (handlers gRPC, envios em virtual threads, rodadas)
mexem no mesmo relógio; sem o lock, dois `tick()` simultâneos poderiam devolver o mesmo valor.

### Detalhes e decisões
- **O relógio também é atualizado na resposta**, não só na requisição. Um RPC tem duas mensagens (ida e volta),
  e as duas carregam `Header.lamport`.
- **`now()` nos logs não incrementa.** Logar não é um evento do algoritmo.
- **Lamport não é tempo real.** Dois nós podem ter valores bem diferentes; o que importa é a ordem causal.
  Por isso o Ricart-Agrawala desempata timestamps iguais pelo id do nó.

Testes: `LamportClockTest` (ver [testes.md](testes.md#lamportclocktest)).
