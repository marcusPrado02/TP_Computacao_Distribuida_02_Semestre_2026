# Pacote `mutex`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md) · Algoritmo: [`../algoritmos/ricart-agrawala.md`](../algoritmos/ricart-agrawala.md)

---

## MutexTransport

Código: [`MutexTransport.java`](../../src/main/java/br/pucminas/cd/aco/mutex/MutexTransport.java)

### Responsabilidade
O que o Ricart-Agrawala precisa da rede, sem saber que é gRPC.

### Implementações
- `PeerNetwork` (produção).
- `RicartAgrawalaTest.FakeTransport` (registra REPLYs, peers mutáveis).
- `RicartAgrawalaSafetyTest.InMemoryNetwork` (rede em memória com atraso aleatório entre 4 nós).

### Métodos
| Método | Contrato |
|---|---|
| `Set<Integer> alivePeers()` | Peers considerados vivos. Consultado no pedido e a cada 500 ms durante a espera. |
| `boolean sendRequest(int to, long timestamp)` | Envia REQUEST; `false` se o peer está inacessível. Bloqueante (até 500 ms em produção). |
| `void sendReply(int to, long requestTs)` | Envia REPLY ao pedido `requestTs`. **Assíncrono**: não pode bloquear, pois é chamado com o lock do algoritmo. |

---

## RicartAgrawala

Código: [`RicartAgrawala.java`](../../src/main/java/br/pucminas/cd/aco/mutex/RicartAgrawala.java)

### Responsabilidade
Exclusão mútua distribuída. Garante que **um nó por vez** execute a seção crítica do sistema:
"evaporar + depositar na matriz de feromônio + propagar a matriz a todos".

### Quem cria / quem usa
- Criado por `Node`.
- `RoundWorker.runRound` chama `acquire()` e, no `finally`, `release()`.
- `NodeServiceImpl` chama `onRequest(ts, from)` (RPC `RequestCS`) e `onReply(from, ts)` (RPC `ReplyCS`).

### Estado (todo protegido por `synchronized (this)`)
| Campo | Significado |
|---|---|
| `state` | `RELEASED` (não quer), `WANTED` (pediu, esperando), `HELD` (dentro da SC) |
| `requestTs` | Timestamp de Lamport do pedido atual; fixo durante toda a espera |
| `awaiting` | Peers dos quais ainda falta REPLY |
| `deferred` | Pedidos adiados: `id do solicitante → timestamp do pedido dele` |
| `LIVENESS_POLL_MS = 500` | Intervalo para reavaliar quem está vivo durante a espera |
| `executor` | Virtual threads para enviar os REQUESTs em paralelo |

### Métodos
| Método | Thread | O que faz |
|---|---|---|
| `void acquire()` | thread da rodada (bloqueia) | Ver fluxo abaixo. Lança `IllegalStateException` se chamado fora de `RELEASED`. |
| `void release()` | thread da rodada | Exige `HELD`; volta a `RELEASED` e envia todos os REPLYs adiados. |
| `void onRequest(long ts, int from)` | thread gRPC | Decide com `shouldDefer`: adia (guarda em `deferred`) ou responde na hora. |
| `synchronized void onReply(int from, long ts)` | thread gRPC | Se `state == WANTED` e `ts == requestTs`, remove `from` de `awaiting` e acorda `acquire`. Senão, descarta (REPLY antigo). |
| `State state()` | testes | Lê o estado (visibilidade de pacote). |
| `void close()` | `Node.close` | Encerra o executor. |

### Fluxo de `acquire()`
1. Com o lock: `state = WANTED`, `requestTs = clock.tick()`, `awaiting = alivePeers()`.
2. Sem o lock: para cada peer, uma virtual thread chama `sendRequest`. Se falhar, chama `onReply(peer, ts)`
   (peer inacessível não disputa, conta como permissão).
3. Com o lock: enquanto `awaiting` não estiver vazio, `wait(500)` e `awaiting.retainAll(alivePeers())`.
4. `state = HELD` e retorna.
5. Se a thread for interrompida durante a espera: volta a `RELEASED`, responde os adiados e relança a exceção.

### A regra (`shouldDefer`)
| Meu estado | Adio o REQUEST (reqTs, reqId)? |
|---|---|
| `HELD` | Sempre |
| `WANTED` | Só se `(requestTs, selfId) < (reqTs, reqId)` (comparação lexicográfica: timestamp, depois id) |
| `RELEASED` | Nunca |

### Thread-safety
- Todo o estado está sob o monitor de `this`; `wait`/`notifyAll` usam o mesmo monitor.
- `sendRequest` (que bloqueia até 500 ms) roda **fora** do lock, em virtual threads.
- `sendReply` é chamado **com** o lock (em `onRequest` não, em `replyDeferred` sim), por isso o contrato exige que seja assíncrono.

### Detalhes e decisões
- **`CsReply.request_ts`:** sem ele, um REPLY atrasado de um pedido antigo (que deu timeout e foi contado como permissão)
  seria contado no pedido **seguinte**, deixando dois nós na SC. `onReply` compara `ts == requestTs`.
- **`wait(500)` em vez de `wait()`:** a espera periódica permite descartar peers que morreram.
  Sem isso, a queda do líder segurando REPLYs adiados travava todos os outros nós.
- **Ordem total `(ts, id)`:** dois pedidos nunca empatam, então exatamente um adia e não há deadlock.
- **`IllegalStateException` em uso errado:** `acquire` reentrante ou `release` sem `HELD` indicam bug no chamador;
  falhar alto é melhor do que corromper o protocolo.
- **Limitação:** o detector de falhas por timeout não distingue lento de morto; um peer lento pode ser tratado como permissão.
