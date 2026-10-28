# Classes de teste

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md)

Os testes ficam em [`src/test/java`](../../src/test/java/br/pucminas/cd/aco/) e rodam com `mvn test` (JUnit 5).
São 26 no total: unitários (algoritmos isolados com fakes) e um de integração (3 nós com gRPC de verdade).

## Por que fakes?

Bully e Ricart-Agrawala dependem só das interfaces `ElectionTransport` e `MutexTransport`, não do gRPC.
Os testes trocam a rede por implementações em memória. Isso dá três vantagens:
- **Determinismo:** o teste decide quem está vivo e quem responde o quê.
- **Velocidade:** sem portas, sem timeouts de rede.
- **Foco:** uma falha aponta para o algoritmo, não para a infraestrutura.

O teste de integração cobre o que os fakes não cobrem: serialização protobuf, interceptors, deadlines e a visão de vivos.

Os testes assíncronos usam um `await(condição)` que verifica a condição a cada 10–50 ms até um limite,
em vez de `Thread.sleep` fixo. Isso evita testes lentos e testes que falham de vez em quando por causa do tempo.

---

## LamportClockTest

Código: [`clock/LamportClockTest.java`](../../src/test/java/br/pucminas/cd/aco/clock/LamportClockTest.java)

| Teste | Verifica |
|---|---|
| `tickIncrementa` | `tick()` devolve 1, depois 2. |
| `recebimentoUsaMaximoMaisUm` | Com relógio em 1, `onReceive(10)` → 11; depois `onReceive(3)` → 12 (remoto atrasado não faz o relógio voltar). |

---

## NodeConfigTest

Código: [`config/NodeConfigTest.java`](../../src/test/java/br/pucminas/cd/aco/config/NodeConfigTest.java)

| Teste | Verifica |
|---|---|
| `parsePeersMantemOrdemEIgnoraEspacos` | `" 3=node3:50051 , 1=10.0.0.1:50052,"` vira mapa com ordem `[3, 1]`, sem espaços, ignorando vírgula final. |
| `parsePeersRejeitaEntradasInvalidas` | Rejeita entrada sem `=`, id não numérico e id repetido. |
| `rejeitaConfiguracaoInconsistente` | Rejeita peer com o próprio id, `nodeId = 0` e `rho = 0`. |
| `tspFileVazioEquivaleAAusente` | `TSP_FILE` em branco é normalizado para `null`. |

---

## BullyElectionTest

Código: [`election/BullyElectionTest.java`](../../src/test/java/br/pucminas/cd/aco/election/BullyElectionTest.java)

**Fake:** `FakeTransport` recebe o conjunto de peers e o subconjunto de "maiores vivos".
`sendElection(to)` registra o envio e devolve `true` só se `to` está vivo; `broadcastCoordinator()` só marca uma flag;
`heartbeat` sempre responde.

| Teste | Cenário | Verifica |
|---|---|---|
| `semMaioresVivosViraLider` | Nó 2, peers {1, 3}, nenhum maior vivo | Vira líder, anuncia COORDINATOR e **não** envia ELECTION ao nó 1 (id menor). |
| `maiorVivoRespondeEAnunciaCoordenador` | Nó 2, nó 3 vivo | Envia ELECTION ao 3; ao receber `onCoordinator(3)`, adota 3 como líder e não se anuncia. |
| `electionDeMenorRespondeOkEDisputa` | Nó 2 recebe ELECTION | De 1 (menor): responde OK e inicia a própria eleição, virando líder. De 3 (maior): responde `false`. |

Nota: `semMaioresVivosViraLider` espera a flag do COORDINATOR com `await`, porque o nó vira líder um instante
**antes** de anunciar; checar logo depois de `isLeader()` dava falha intermitente.

---

## RicartAgrawalaTest

Código: [`mutex/RicartAgrawalaTest.java`](../../src/test/java/br/pucminas/cd/aco/mutex/RicartAgrawalaTest.java)

**Fake:** `FakeTransport` com peers **mutáveis** (para simular queda), `sendRequest` sempre entregue
e `sendReply` que só registra o destino em `repliesSent`.

**Auxiliar `wantingNode`:** cria o nó 2, chama `acquire()` numa virtual thread e espera o estado `WANTED`.
Como o relógio começa em 0, o pedido fica com `ts = 1`, ou seja, `(1, 2)`. Como o fake nunca responde sozinho,
o nó fica parado em `WANTED`, pronto para receber REQUESTs de teste.

| Teste | Verifica |
|---|---|
| `releasedRespondeNaHora` | Em `RELEASED`, REQUEST de 1 recebe REPLY imediato. |
| `heldAdiaEAoLiberarResponde` | Sem peers, `acquire` entra direto em `HELD`; REQUEST é adiado e respondido só no `release()`. |
| `wantedComPedidoMaisAntigoRespondeNaHora` | Meu `(1,2)` vs. `(0,3)`: o outro é mais antigo → respondo. |
| `wantedComPedidoMaisNovoAdia` | Meu `(1,2)` vs. `(7,3)`: eu tenho prioridade → adio. |
| `empateNoTimestampDesempataPeloId` | `(1,1)` vence `(1,2)` → respondo; `(1,2)` vence `(1,3)` → adio. |
| `replyDePedidoAntigoNaoLiberaPedidoAtual` | `onReply(3, 0)` (pedido antigo) não libera; `onReply(3, 1)` libera e o estado vira `HELD`. |
| `peerQueCaiDuranteEsperaDeixaDeSerAguardado` | Remover o nó 3 dos vivos faz o `acquire` concluir (verificação a cada 500 ms). |
| `releaseForaDaSecaoCriticaEhErro` | `release()` sem `HELD` lança `IllegalStateException`. |

---

## RicartAgrawalaSafetyTest

Código: [`mutex/RicartAgrawalaSafetyTest.java`](../../src/test/java/br/pucminas/cd/aco/mutex/RicartAgrawalaSafetyTest.java)

**Fake:** `InMemoryNetwork` liga 4 instâncias reais de `RicartAgrawala`, cada uma com seu `LamportClock`.
- `sendRequest`: incrementa o relógio do remetente, espera 0–2 ms, aplica `onReceive` no destino e chama `onRequest`.
- `sendReply`: incrementa o relógio e entrega **de forma assíncrona** (virtual thread) após 0–2 ms.

O atraso aleatório força intercalações diferentes a cada execução.

| Teste | Verifica |
|---|---|
| `nuncaHaDoisNosNaSecaoCritica` | Cada nó entra 30 vezes na SC, todos ao mesmo tempo. Um contador compartilhado mede quantos estão dentro: o máximo deve ser **1** (segurança). Todas as 120 entradas acontecem (sem deadlock), e todos terminam em `RELEASED`. Limite de 60 s. |

**Esse teste realmente pega erro?** Sim. Trocando a regra de `WANTED` por "nunca adiar", ele falha com
"expected: <1> but was: <4>".

---

## AntColonyTest

Código: [`aco/AntColonyTest.java`](../../src/test/java/br/pucminas/cd/aco/aco/AntColonyTest.java)

| Teste | Verifica |
|---|---|
| `encontraOtimoEmCirculo` | 12 cidades num círculo: o ótimo é o próprio polígono (conhecido sem precisar de busca). Em 20 iterações de 20 formigas, o ACO sequencial (mesmas classes do nó) encontra exatamente esse comprimento. |

---

## PheromoneMatrixTest

Código: [`aco/PheromoneMatrixTest.java`](../../src/test/java/br/pucminas/cd/aco/aco/PheromoneMatrixTest.java)

Usa duas rotas fixas: `TOUR_A` (comprimento 10) e `TOUR_B` (comprimento 12), com ρ = 0,1.

| Teste | Verifica |
|---|---|
| `flattenEReplaceSaoInversos` | `replace(flatten())` reproduz a matriz exatamente. |
| `replaceRejeitaTamanhoErrado` | Matriz 3×3 numa réplica 4×4 → `IllegalArgumentException`. |
| `semExclusaoMutuaAsReplicasDivergemEPerdemDeposito` | Dois nós atualizam a própria réplica e trocam o resultado: as réplicas ficam **diferentes** e nenhuma igual à aplicação em série (*lost update*). |
| `comExclusaoMutuaAsReplicasConvergem` | Em série (N1 atualiza e propaga; N2 atualiza sobre a matriz já nova): réplicas **idênticas** e iguais à aplicação em série. |

Este é o argumento concreto de **por que** o sistema precisa de exclusão mútua.

---

## ClusterIntegrationTest

Código: [`ClusterIntegrationTest.java`](../../src/test/java/br/pucminas/cd/aco/ClusterIntegrationTest.java)

Sobe 3 objetos `Node` reais na mesma JVM, cada um numa porta livre (`ServerSocket(0)`), falando gRPC por `localhost`.
Instância pequena: 12 cidades, semente 7, 5 formigas, `START_DELAY_MS = 500`. Limite de 90 s por teste;
`@AfterEach` fecha todos os nós.

| Teste | Verifica |
|---|---|
| `elegeMaiorIdExecutaTodasAsRodadasEReplicasConvergem` | Com 3 rodadas: todos concordam que o líder é N3; N3 conclui; todos viram a rodada 3, têm uma melhor rota, e as três matrizes de feromônio são **idênticas**. |
| `quedaDoLiderElegeOProximoMaiorId` | Com 1000 rodadas (para não acabar antes), fecha N3. Em até 20 s, N1 e N2 concordam que o líder é N2 (≈5 s de heartbeats perdidos + eleição). |
| `chamadaSemTokenOuDeRemetenteDesconhecidoEhRecusada` | Um cliente gRPC avulso: sem token → `UNAUTHENTICATED`; com token mas `sender_id = 99` → `PERMISSION_DENIED`. |

Observação: os logs desse teste saem com o mesmo `[N..]`, porque `Log` é global ao processo.

### Problema conhecido ao rodar com o VS Code aberto
A extensão Java do VS Code também compila em `target/classes`. Se ela fizer isso com o código gRPC gerado
ainda ausente, os testes falham com `Unresolved compilation problem`. Não é bug do código: rode
"Java: Clean Java Language Server Workspace" ou rode `mvn clean test` com a IDE fechada.
