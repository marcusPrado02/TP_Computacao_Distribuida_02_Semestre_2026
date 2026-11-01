# Arguição — quem responde o quê

Base: `docs/ENUNCIADO.md` §4.2 (*Observação importante*):

> "todos os alunos do grupo [devem ser] capazes de responder perguntas básicas a respeito do sistema
> implementado em um contexto geral. Além disso, cada aluno deve ser capaz de responder com clareza
> às perguntas relacionadas especificamente às partes do trabalho em que atuou diretamente. Caso os
> alunos não consigam responder, a nota do grupo como um todo poderá ser prejudicada."

Duas consequências práticas:

1. **Existem perguntas gerais** (§1 deste documento) que qualquer um dos quatro deve responder.
2. **Cada um tem uma fatia** (§2 em diante) que precisa sustentar sozinho, com comando e tela.

## Regra de honestidade

O relatório diz que cada aluno é **responsável** por um módulo — responsável pela demonstração e
pela explicação, não necessariamente por ter digitado o código. Na arguição, use o verbo certo:

| Situação | Diga | Não diga |
|---|---|---|
| você escreveu o código | "implementei X" | — |
| você estudou e reproduziu | "acompanhei e reproduzi X; o código está em `classe.java:linha`" | "eu que fiz" |
| você rodou o ensaio e mediu | "rodei o ensaio e medi Y" | "implementei o ensaio" |

Quem inventa autoria perde a arguição na primeira pergunta de acompanhamento ("me mostra a linha").

## Teste de prontidão (objetivo, faça antes da entrega)

Para a sua fatia:

1. explicar o módulo em **2 minutos, sem anotações**;
2. responder as **3 perguntas** da sua seção;
3. rodar **1 comando** que mostra o módulo funcionando, do zero.

Quem falha no item 3 não é dono da fatia, independentemente do que diz o relatório.

---

# 1. Perguntas gerais (todos os quatro)

| Pergunta | Resposta curta |
|---|---|
| Qual é o problema e por que distribuir? | Caixeiro-viajante (NP-difícil) resolvido por colônia de formigas; cada formiga é independente, então o trabalho divide quase linearmente e o único estado compartilhado é a matriz de feromônio. |
| Qual é a arquitetura em uma frase? | N nós simétricos (mesmo binário) que trocam mensagens por gRPC; um deles é eleito líder (maior id vivo) e dispara as rodadas; a matriz de feromônio é replicada e só muda dentro da seção crítica. |
| Quantos requisitos o enunciado pedia e quantos vocês fizeram? | No mínimo dois; fizemos quatro: RPC/gRPC, relógio lógico de Lamport, eleição de líder (Bully) e exclusão mútua (Ricart-Agrawala). |
| Por que quatro, e não dois? | Os quatro se encaixam no mesmo fluxo: o líder (eleição) dispara a rodada, a seção crítica (exclusão mútua) protege a matriz, o relógio ordena os pedidos, e tudo isso é mensagem (gRPC). |
| Onde roda? | Docker Compose (4 contêineres em uma máquina) e em máquinas separadas na mesma LAN, com IPs fixos e `PEERS` por IP. |
| Quantos testes? | 43 testes em 12 classes, `mvn test`, 0 falhas. |
| Qual é a maior limitação? | O detector é por timeout: não distingue nó lento de nó morto. Uma falha conta como permissão, então dois nós podem entrar juntos na seção crítica se um só estiver lento (F1, em `docs/AUDITORIA.md`). |
| O que fica para a Entrega 2? | Tratamento de falhas (obrigatório), controle de réplicas e análise de desempenho 1×N nós. |

Números que podem ser cobrados de qualquer um: **berlin52 = 52 cidades, ótimo 7542**;
**rodada ≈ 0,94 s** com `ITERATIONS_PER_ROUND=1000`; **failover ≈ 10 s** no perfil LAN
(≈ 8,5 s de detector); **1ª rodada após a queda: 0,56 s** (era 15,6 s antes da correção F14).

---

# 2. Pedro — comunicação e infraestrutura

**Módulo:** `rpc/` (580 linhas) + `config/` (170) · `node.proto`, `NodeMain`, `Dockerfile`,
`docker-compose*.yml`, `scripts/tp.sh` · **Seções do relatório:** §2 (arquitetura), §3.1 (gRPC) e
§4 (resultados, incluindo a Tabela 1). **Entregável:** ensaio em 2 máquinas, com logs em
`docs/evidencias/` (roteiro em `docs/LAN.md` §2) e a montagem final do PDF.

### Demo

```bash
./tp up -n 4 -r 10 -a 40        # sobe 4 nós nesta máquina
./tp status                     # líder visto, rodada e relógio de cada nó
./tp test HandshakeTest         # token/PEERS divergentes são recusados na subida
```

Se houver 2 máquinas: um nó por máquina seguindo `docs/LAN.md` §2, e mostrar nos dois logs
`Novo líder: N4` e a mesma melhor rota.

### As 3 perguntas

1. **Por que 64 MiB e não os 4 MiB padrão do gRPC?**
   A mensagem de `UpdatePheromone` carrega a matriz n×n de `double`. A 4 MiB, a partir de ~725
   cidades a mensagem estoura e o peer é marcado como morto — falha silenciosa, porque o sintoma
   parece queda de rede (F8). Acima de ~2900 cidades o limite de 64 MiB também estoura: não há
   *chunking*, e essa é uma limitação conhecida.
2. **O que o token protege e o que ele NÃO protege?**
   Protege contra um processo fora do cluster falar com um nó: o interceptor exige
   `x-cluster-token` no *metadata* e devolve `UNAUTHENTICATED`; o serviço ainda recusa `sender_id`
   fora de `PEERS`. Não protege contra configuração divergente *entre* membros legítimos — disso
   cuida o handshake do startup (token, remetente e conjunto `{self} ∪ PEERS`, senão encerra com
   código 2). E dois grupos que não compartilham nenhum membro ainda não são detectados.
3. **Por que o broadcast de feromônio espera só os peers vivos?**
   Porque ele roda *dentro* da seção crítica. Esperar um peer morto até o fim do
   `BROADCAST_TIMEOUT_MS` fazia cada nó da fila gastar o timeout inteiro na SC: a primeira rodada
   após a queda do líder levava 15,6 s. Esperando só os vivos (o morto recebe em segundo plano), caiu
   para 0,56 s (F14, `BroadcastPheromoneTest`).

Extra (porque a §4 é sua): **de onde vêm os números da Tabela 1?** — 43 testes do `mvn test`;
0,94 s de mediana por rodada com `I=1000`; 7542 do ensaio de berlin52; ~10 s do perfil LAN;
0,56 s medido depois do F14; 4000 rodadas da auditoria de 20/10. A legenda diz explicitamente que
as medidas foram em **uma** máquina com 4 contêineres e que não medimos speedup 1×N.

---

# 3. Marcus — relógio lógico e exclusão mútua

**Módulo:** `mutex/` (212 linhas) + `clock/` (43) · **Seções do relatório:** §3.3, §3.4 e
§5 (desafios). **Entregável:** `./tp check` e `./tp timeline cs` com a saída arquivada em
`docs/evidencias/` — é o `check` que sustenta a frase do §4 ("nunca houve dois nós na seção
crítica").

### Demo

```bash
./tp demo mutex        # sobe 4 nós, roda as rodadas e imprime a SC em ordem de Lamport
./tp check             # verifica nos logs que nunca houve 2 nós na SC
```

### As 3 perguntas

1. **Por que 2(N−1) mensagens por entrada?**
   Ricart-Agrawala: o nó manda 1 `REQUEST` para cada um dos N−1 peers e recebe 1 `REPLY` de cada —
   (N−1) + (N−1) = 2(N−1). É ótimo em número de mensagens por entrada; o custo é que todos falam
   com todos (sem coordenador).
2. **O que impede um `REPLY` atrasado de valer para um pedido novo?**
   O `REPLY` carrega o `request_ts` do pedido que ele está autorizando. O nó só conta a permissão se
   o carimbo bate com o pedido corrente; `REPLY` de pedido antigo é descartado
   (`RicartAgrawala.java:138`). Sem isso, uma permissão perdida no caminho liberaria uma entrada
   futura indevidamente.
3. **O que acontece se dois nós entrarem na seção crítica juntos?**
   As duas escritas concorrentes na matriz perdem atualização: o último `applyPheromone` sobrescreve
   o outro e as réplicas divergem (`PheromoneMatrixTest`). Com exclusão, as matrizes terminam
   idênticas nos 4 nós (`ClusterIntegrationTest`). É por isso que a SC não é opcional.

Extra que costuma cair: *"e se o peer morrer depois de receber o REQUEST?"* — o pedido é reenviado a
cada 500 ms e quem deixou de estar vivo não é mais aguardado (`RicartAgrawala.java:39,93-99`),
senão o `acquire()` ficaria preso para sempre (F2/F3).

Extra (porque a §5 é sua): **por que o F1 não foi corrigido?** — porque separar "lento" de "morto"
exige quórum ou consenso (2PC/Raft); preferimos manter o cluster disponível quando um nó realmente
cai, e assumimos o risco de dois nós entrarem juntos na SC. É decisão de projeto, documentada em
`docs/AUDITORIA.md` F1 e na §5 do relatório. Os outros dois desafios da §5 também são seus: REQUEST
sem REPLY (F2/F3) e configuração divergente virando split-brain silencioso (F9).

---

# 4. Gabriel — ACO e rodadas

**Módulo:** `aco/` (361 linhas) + `round/` (336) · **Seções do relatório:** §1 (introdução) e
§2.3 (uma rodada). **Entregável:** remediar o ensaio de berlin52 e entregar o log com os tempos por
rodada e a melhor rota (`FIM: melhor rota = 7542`), em `docs/evidencias/` — os números alimentam a
Tabela 1, que fica com o Pedro.

### Demo

```bash
./tp up -n 4 -r 20 -a 40 -f data/berlin52.tsp -i 1000
./tp logs rodada -f          # LÍDER: iniciando rodada N/20 ... e FIM: melhor rota = 7542
```

### As 3 perguntas

1. **Por que as formigas paralelizam bem?**
   Cada formiga é independente: lê o snapshot da matriz, constrói um passeio e devolve o custo.
   Não há escrita compartilhada na parte cara — só leitura. O que precisa de coordenação é apenas a
   atualização da matriz, que é O(n²) e roda uma vez por rodada.
2. **Por que a seção crítica é necessária se só um campo muda?**
   Porque a atualização é *evaporar + depositar* sobre a matriz inteira, feita por todos os nós sobre
   a mesma réplica. Duas atualizações concorrentes não comutam: a última escrita apaga o depósito da
   outra e as réplicas divergem. A SC garante a mesma ordem em todos os nós
   (`PheromoneMatrixTest` mostra a divergência sem ela).
3. **O que `ITERATIONS_PER_ROUND` muda e o que NÃO muda?**
   Muda só a parte local: repete o lote de formigas sobre o mesmo snapshot e fica com a melhor rota,
   alongando a rodada para a demo (~1 s com 1000). **Não** muda o protocolo: continua um `StartRound`,
   uma entrada na seção crítica e um `UpdatePheromone` por rodada. A iteração 0 usa a semente
   original, então `I=1` reproduz o comportamento anterior.

---

# 5. Eduardo — eleição e detector de falha

**Módulo:** `election/` (271 linhas) · **Seção do relatório:** §3.2 (Bully e detector).
**Entregável:** rodar o failover cronometrado e registrar os tempos reais (`docs/LAN.md` §3.1) — é a
linha de *failover* da Tabela 1 e a evidência de que a detecção leva ~8,5 s.

### Demo

```bash
./tp demo failover     # elege, derruba o líder, mede a reeleição, religa e confere com ./tp check
```

O que aparece: `Líder inicial: N4` → `Novo líder: N3 em ~Xs` → `N4 reassumiu a liderança`.

Também vale mostrar a detecção bruta, que é o que a banca cobra em tempo:

```bash
./tp up -n 4 -r 500 && ./tp kill 4 && ./tp logs eleicao -f
# ~8,5 s de "não respondeu" (5 heartbeats) e então "Novo líder: N3"
```

### As 3 perguntas

1. **Por que o maior id vence?**
   O Bully compara identificadores: a candidatura "sobe" até o maior nó vivo. Isso dá um critério
   determinístico e sem empate — dois nós nunca se elegem ao mesmo tempo, desde que um enxergue o
   outro. O id não tem relação com capacidade, é só desempate.
2. **Por que 5 heartbeats e não 1?**
   Um timeout isolado pode ser pausa de GC ou pico de rede; eleger à toa custa uma rodada e troca de
   líder desnecessária. Com heartbeat de 1 s e 5 faltas, o falso positivo fica improvável — o preço
   é a latência de detecção: ~5 s no compose e ~8,5 s no perfil LAN (heartbeat de 1,5 s).
3. **Por que o detector é separado do Bully?**
   Porque respondem perguntas diferentes: o `LeaderMonitor` decide *quando* desconfiar (5 faltas de
   heartbeat) e o `BullyElection` decide *quem* assume. Juntar os dois acoplaria o tempo de detecção
   ao algoritmo de eleição, e um falso positivo passaria a custar uma eleição inteira.
   *("Lento ≠ morto" é a pergunta geral — a resposta está na §5, que é do Marcus.)*

Extra: *"e se dois nós iniciarem eleição ao mesmo tempo?"* — cada um só fala com ids maiores e quem
recebe `ELECTION` de id menor responde OK e inicia a própria; a disputa converge para o maior id vivo,
e um `COORDINATOR` de id menor é contestado (`BullyElection.java:124-142`).

---

# 6. Roteiro da apresentação (10 min, Entrega 2)

A ordem segue a demo, não os módulos: cada um fala enquanto algo acontece na tela.

| Tempo | Quem | O que faz na tela | O que diz |
|---|---|---|---|
| 0:00–2:00 | Pedro | `./tp up -n 4` e `./tp status` | problema, arquitetura, nós simétricos, eleição inicial (`Novo líder: N4`) |
| 2:00–4:00 | Gabriel | `./tp logs rodada -f` com berlin52 | uma rodada: formigas, seção crítica, convergência até 7542 |
| 4:00–6:00 | Eduardo | `./tp demo failover` | detector (5 heartbeats), Bully, ~10 s até `Novo líder: N3` |
| 6:00–8:00 | Marcus | `./tp timeline cs` e `./tp check` | ordem de Lamport na SC, nenhum par de `ENTROU` sem `SAIU`; F1 e o que ficou em aberto |
| 8:00–10:00 | Pedro | Tabela 1 e slides | resultados (0,94 s/rodada, 7542, ~10 s de failover) e o que entra na Entrega 2 |

Quem opera o teclado na demo é quem responde pela demo. Ensaie com o cluster já rodando: subir do zero
leva ~20 s e não pode ser descoberto no meio da apresentação.

---

# 7. Como manter isto verdadeiro

- Se a divisão mudar, mude **`relatorio/secoes/06-papeis.tex`** e este arquivo juntos.
- Cada item da tabela de entregáveis deve ter um artefato em `docs/evidencias/` (log, print ou
  arquivo de saída). Sem artefato, a frase do §6 vira promessa, não fato.
- O material de estudo por algoritmo está em `docs/algoritmos/` (§9 de cada um tem a lista completa
  de perguntas prováveis): `aco-tsp.md` (12), `bully.md` (10), `lamport.md` (9),
  `ricart-agrawala.md` (8).

## Por que Pedro e Marcus concentram as seções

Duas razões, e nenhuma delas é hierarquia:

1. **Volume de texto e de evidência.** Pedro responde por §2 (arquitetura, o item mais detalhado do
   enunciado), §3.1, §4 com a Tabela 1, o ensaio em duas máquinas e a montagem final — as partes que
   o professor mais cobra e as únicas com medição de campo. Marcus responde por dois requisitos
   (Lamport + Ricart-Agrawala), pela §5 inteira (F1/F2/F3 são do mutex) e pela ferramenta `./tp check`,
   que é a prova citada no §4.
2. **O que o git comprova.** `git log --format=%an -- src/main` tem só dois autores: Marcus (6
   commits) e Pedro (5). Gabriel e Eduardo entram na etapa de estudo, ensaio e apresentação. O §6 não
   afirma autoria de código de ninguém ("ficou com", "ficou responsável por") — afirma
   responsabilidade pela demonstração, que é o que a arguição cobra.

Se Gabriel ou Eduardo quiser assumir mais, a troca natural é dar a §5 a quem fizer o ensaio de
failover completo (F1 + F2/F3 + F9), e não dividir a §3 em pedaços menores.
