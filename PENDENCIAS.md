# Pendências — Entrega 1

> Fotografia de **28/10/2026**, a partir do commit `176620a` (*Endurece o cluster para LAN…*);
> **atualizada em 01/11/2026**, já com a `main` mesclada (`99d7525` + relatório):
> 2.1, 2.2, 2.3 e 2.4 resolvidos; correções F14 e F15 em `docs/AUDITORIA.md`; andaime do
> relatório e roteiros completos em `relatorio/`.
> Este arquivo é a **lista de ação** (o que fazer, como verificar, quando está pronto).
> O plano por pessoa continua em [`TODO.md`](TODO.md); o roteiro do ensaio físico, em
> [`docs/LAN.md`](docs/LAN.md).

**Resumo (01/11):** 0 bloqueadores técnicos. Falta: ensaio físico em 2 máquinas (1.2, recomendado),
estudo do Bully por C (2.4, material pronto), **reescrever o texto do relatório** (R6 — ver o aviso
na §3) e o push da `main`.

| # | Item | Dono (TODO.md) | Bloqueia a entrega? |
|---|---|---|---|
| 1.1 | Reconciliar `main` com `entrega1/validacoes` | A (todos revisam) | **Feito** (`3290e09`) |
| 1.2 | Ensaio mínimo em **2** máquinas | A | Não — recomendado (precisa de 2 notebooks) |
| 2.1 | Rodada de ~1 s | B | **Feito** (`ITERATIONS_PER_ROUND`) |
| 2.2 | `berlin52.tsp` via `TSP_FILE` | B | **Feito** (chega ao ótimo 7542) |
| 2.3 | Roteiro de failover | C | **Feito** (`docs/LAN.md` §3.1, cronometrado) |
| 2.4 | Revisar `BullyElection` | C | Material pronto; falta C estudar (arguição) |
| 3.x | Relatório SBC | todos | **Sim** |

## 0. O que já está pronto (não refazer)

- Núcleo distribuído: gRPC (8 RPCs unários, sem streaming), eleição Bully com detector de líder,
  relógio de Lamport, Ricart-Agrawala, formigas/rodadas, réplica da matriz de feromônio.
- **43 testes verdes** em 12 classes (`mvn test`, 01/11, recontado após o merge `99d7525`):
  `RicartAgrawalaTest` (11), `RicartAgrawalaSafetyTest` (1), `NodeConfigTest` (7), `TspInstanceTest` (6),
  `PheromoneMatrixTest` (4), `ClusterIntegrationTest` (4), `BullyElectionTest` (3), `LamportClockTest` (2),
  `AntColonyTest` (1), `RoundWorkerTest` (2), `HandshakeTest` (1), `BroadcastPheromoneTest` (1).
- Ensaio local que imita LAN: `docker-compose.lan.yml` (IPs fixos), timeouts por env,
  barreira `PEER_WAIT_MS`, broadcast que espera **só** peer vivo (F14).
- `data/berlin52.tsp` (TSPLIB, ótimo 7542) e `ITERATIONS_PER_ROUND` (rodada de ~1 s).
- Handshake de configuração no startup (token, remetente e conjunto `{self} ∪ PEERS`).
- Docs: `docs/ARQUITETURA.md` (com limitações conhecidas §6), `docs/LAN.md`, `docs/AUDITORIA.md`
  (F1–F15), `docs/ENUNCIADO.md`, `README.md`.
- Relatório: andaime no template SBC + roteiro completo por seção em `relatorio/`. A prosa é do grupo.

> Sem `mvn`/`java` no `PATH` desta máquina? Rode a suíte dentro da imagem de build:
> ```bash
> docker run --rm -v "$PWD":/app -w /app maven:3.9.9-eclipse-temurin-21 mvn -q -B test
> ```

## 1. Antes da entrega

### 1.1 Reconciliar `main` com `origin/entrega1/validacoes`

- [x] **Feito em `3290e09`** (merge `--no-ff` com os 3 conflitos resolvidos; suíte de 40 testes verde
  na época — hoje 43; compose LAN com 4 nós, barreira e failover N4 → N3 em ~7,5 s no perfil antigo,
  ~10 s com `RPC_TIMEOUT_MS=1500`).

Três commits de 20/10 (`e46a3a0`, `8f3ba03`, `ee09e5d`) **nunca entraram na `main`**. Eles contêm
trabalho que a entrega usa: parser TSPLIB em `TspInstance`, validação de token/membros no startup
(`HandshakeTest`), limite de mensagem gRPC, `docs/ENUNCIADO.md`, `docs/AUDITORIA.md`, testes novos
(`TspInstanceTest`, `RoundWorkerTest`). A `main` seguiu por outro caminho (`7d87660` da PR #2 →
`176620a`), então **as duas linhas divergiram** a partir de `ceea7b3`.

O merge conflitava em 3 arquivos:

```
CONFLICT (content): docs/ARQUITETURA.md
CONFLICT (content): src/main/java/br/pucminas/cd/aco/Node.java
CONFLICT (content): src/main/java/br/pucminas/cd/aco/rpc/PeerNetwork.java
```

Como ficou resolvido (para quem for revisar):
- `PeerNetwork`: timeouts configuráveis + handshake/quarentena/64 MiB; **heartbeat =
  `max(1 s, RPC_TIMEOUT_MS)`**, mantendo o piso de 1 s e acompanhando a LAN.
- `broadcastPheromone`: filtro `id == coordinator || shouldContact(id)` — o coordenador é sempre
  tentado, os mortos ficam em quarentena.
- `Node`: `PeerNetwork` com timeouts + `setConfigErrorHandler`; `BullyElection` com `electionMs`;
  `RoundWorker` sem `leaderId`.
- `RoundWorkerTest`/`HandshakeTest`: `NodeConfig` agora tem o 14º componente `Timeouts`.

> Risco: os dois lados mexeram em `Node`, `PeerNetwork` e eleição. Conflito resolvido "no braço"
> compila e ainda quebra o handshake em runtime — por isso o critério é teste de integração, não build.

### 1.2 Ensaio mínimo em 2 máquinas (recomendado, não bloqueia)

- [ ] **Rodar uma vez em 2 notebooks e guardar a prova.**

O enunciado (`docs/ENUNCIADO.md` §3.1) pede que o sistema "possa ser executado em máquinas
conectadas a uma rede local" — é requisito de **capacidade**, não de evidência. A Entrega 1 é só o
relatório de 4 páginas; a demonstração ao vivo é da **Entrega 2**. Então o ensaio físico **não
bloqueia** esta entrega, e a validação já registrada em Docker é forte (`docs/AUDITORIA.md`: 38 testes
na época, hoje 43; 4 nós no compose, 4000 rodadas, falha de líder e de seguidor).

Ainda assim vale ~30 minutos: fecha a ressalva anotada no topo de `docs/LAN.md` ("a evidência que
conta é o cluster rodando em máquinas diferentes") e protege contra a pergunta "isso só roda em
Docker?" na arguição. **Duas máquinas bastam** — não precisa mobilizar 4 pessoas para a Entrega 1.

Mesmo commit nas duas, porta 50051 liberada, mesma rede, mesmo `CLUSTER_TOKEN`
(roteiro completo: [`docs/LAN.md`](docs/LAN.md) §2).

| Máquina | IP (`hostname -I`) | `NODE_ID` | `PEERS` |
|---|---|---|---|
| notebook A | | 1 | `2=<IP-do-B>:50051` |
| notebook B | | 2 | `1=<IP-do-A>:50051` |

```bash
export CLUSTER_TOKEN='<igual nas duas>'
export NODE_ID=1 PEERS="2=<IP-do-B>:50051"
export TSP_CITIES=60 TSP_SEED=42 ROUNDS=30 ANTS=40
export RPC_TIMEOUT_MS=1500 BROADCAST_TIMEOUT_MS=5000 COORDINATOR_TIMEOUT_MS=8000
export START_DELAY_MS=2000 PEER_WAIT_MS=120000
java -jar target/aco-node.jar        # ou: docker run --rm --network host -e ... aco-node
```

Evidências a salvar em `docs/evidencias/` (print ou `.log`), com data, IP e hash do commit:

- [ ] `hostname -I` + `ss -ltn | grep 50051` nas duas (nó ouvindo).
- [ ] `Novo líder: N2` nas duas no mesmo intervalo (maior id vence).
- [ ] `LÍDER: todos os 1 peers responderam; iniciando as rodadas` (barreira ligada).
- [ ] `LÍDER: rodada 30 concluída; melhor global = ...` e `FIM: melhor rota = ...` **iguais** nos dois logs.
- [ ] `[L=...]` de Lamport crescendo nos logs.
- [ ] **Failover:** `Ctrl+C` na máquina do líder → `Novo líder: N1` na sobrevivente e a rodada
  seguinte com `[1]`.

**Pronto quando:** as duas máquinas terminam as 30 rodadas com a mesma melhor rota e o failover
aparece nos logs. Se o Wi-Fi da faculdade bloquear tráfego entre hosts (ver `docs/LAN.md` §4),
registrar o diagnóstico e seguir com a evidência Docker — **não é bloqueador**.

> O ensaio de 4 máquinas com as 6 evidências completas de `docs/LAN.md` §3 passa a ser item da
> **Entrega 2**, junto com a apresentação de 10 min.

## 2. Ajustes técnicos antes da demo

### 2.1 Rodada de ~1 s (era ~70 ms)

- [x] **Feito em 01/11.** Variável nova `ITERATIONS_PER_ROUND` (padrão 1, opção 3 abaixo): repete o lote
  de formigas sobre o mesmo snapshot e fica com a melhor rota; rede e SC continuam 1× por rodada.
  Medido com 4 nós numa máquina de 16 núcleos, berlin52, 40 formigas (mediana por rodada):
  1 → 65 ms · 20 → 76 ms · 300 → 387 ms · **1000 → 943 ms** (mín. 843, máx. 1357).
  Em 4 máquinas cada nó tem a CPU inteira: **meça de novo** no ensaio físico e ajuste.
  Uso: `ITERATIONS_PER_ROUND=1000` (compose/`.env`) ou `./tp up -i 1000`. Documentado em
  `README`, `ARQUITETURA` §8, `docs/classes/config.md` e `docs/guia-de-execucao.md`.
  Testes: `NodeConfigTest.iteracoesPorRodadaPadraoEhUmEZeroEhRejeitado` e
  `RoundWorkerTest.maisIteracoesPorRodadaNuncaPioramARotaLocal`.

<details><summary>Análise original</summary>


Medir: `docker compose -f docker-compose.lan.yml logs | grep "rodada .* concluída"` (ou o
timestamp de `Rodada N: melhor local` / `SAIU da seção crítica`).

Opções, da menos para a mais invasiva:

1. `ANTS` maior — paraleliza por formiga, ganho sublinear (8 núcleos ≈ 4–6×, não 15×).
2. Instância maior (`TSP_FILE` com `berlin52.tsp` ainda é pequena para isso — ver 2.2).
3. **Preferível:** `ITERATIONS_PER_ROUND` (novo, padrão 1) em `RoundWorker.runRound`, repetindo
   `colony.runAnts(...)` com sementes diferentes e depositando uma vez no fim — mantém o custo de
   rede igual e só alonga a parte local.

**Pronto quando:** 3 rodadas seguidas entre ~0,8 s e ~1,5 s por nó (o teto de 30 s do líder está
fixo em `RoundCoordinator.ROUND_TIMEOUT_MS`, então 1 s por rodada dá folga de 30×), e
`README`/`ARQUITETURA` §8 documentando a variável nova.

</details>

### 2.2 Instância TSPLIB (`berlin52.tsp`)

- [x] **Feito em 01/11.** `data/berlin52.tsp` versionado (origem e ótimo em `data/README.md`).
  4 nós, 20–30 rodadas: melhor rota **7542 = ótimo conhecido** em todos. Os dois compose aceitam
  `TSP_FILE=/app/data/berlin52.tsp` do ambiente/`.env` (a imagem já copia `data/`).

`data/` só tem `README.md`. Baixar `berlin52.tsp` do TSPLIB, colocar em `data/` e conferir que
`TspInstance` lê `EDGE_WEIGHT_TYPE: EUC_2D` (o parser vem do branch `entrega1/validacoes`, item 1.1 —
fazer **depois** do merge, para não testar duas vezes).

Atenção em contêiner: o `Dockerfile` copia `data/` **no build**. Para trocar a instância sem rebuild,
monte o volume (`-v "$PWD/data:/app/data:ro"` no `docker run`, ou `volumes:` no compose) e aponte
`TSP_FILE=/app/data/berlin52.tsp`.

**Pronto quando:** os 4 nós logam a mesma distância para a melhor rota do arquivo, e o resultado é
comparável ao ótimo conhecido (7542 para `berlin52`, como sanity check, não como exigência).

### 2.3 Roteiro de demo do failover

- [x] **Feito em 01/11:** roteiro cronometrado em [`docs/LAN.md` §3.1](docs/LAN.md). O ensaio revelou
  que a 1ª rodada depois da queda levava **15,6 s** (broadcast esperando o líder morto dentro da SC);
  corrigido (F14 em `docs/AUDITORIA.md`), agora **0,56 s**. Tempos no perfil LAN: `Novo líder: N3`
  em ~10 s, rodada 12 começa em ~12 s. Falta só **ensaiar a fala** (C).

Cena: cluster rodando → `docker compose stop -t 0 node4` (ou fechar a máquina) → em ~2 s os peers
logam `N4 inacessível (DEADLINE_EXCEEDED)`, em ~8,5 s `Líder N4 não responde há 5 heartbeats;
iniciando eleição` e em ~10 s `Novo líder: N3`; a rodada seguinte sai com `[1, 2, 3]`
e o relógio de Lamport continua crescendo. Ensaiar o timing para não ficar em silêncio na
apresentação (ver 2.1).

**Pronto quando:** o roteiro está no `docs/LAN.md` §3 ou no relatório, com comandos exatos e
o tempo esperado de cada passo.

### 2.4 Revisar `BullyElection` (arguição)

- [ ] **Ler `election/` inteiro e conseguir explicar sem tela.** Material pronto em
  `docs/algoritmos/bully.md` §9 — incluídas as respostas para "dois nós iniciam eleição ao mesmo
  tempo" (Q9) e "por que eleição e detector são separados" (Q10). Falta C estudar.

Pontos que a banca costuma cobrar: quem dispara a eleição, o que acontece com id maior/menor,
o que é `LeaderMonitor` (5 heartbeats perdidos) e por que eleição e detector são separados.

**Pronto quando:** C explica o fluxo em 2 minutos e responde "e se dois nós iniciarem eleição ao
mesmo tempo?".

## 3. Relatório (template SBC, até 4 páginas)

**Texto completo em `relatorio/`** — compila para `relatorio/main.pdf` em **4 páginas**, com
1 figura, 1 tabela e 5 referências. Não precisa de TeX no host:

```bash
cd relatorio && docker run --rm -v "$PWD":/w -w /w texlive/texlive:latest \
  latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex
```

> **DECISÃO REGISTRADA (01/11):** o texto foi escrito com assistência de IA a pedido explícito do
> grupo, que assumiu as consequências. O enunciado (§5.1) diz que texto praticamente gerado de
> forma automática é **desconsiderado**. Reescrita com palavras próprias é o item R6 abaixo.

- [x] Template SBC vendorizado (`relatorio/sbc-template.sty`) + `main.tex` com a estrutura
- [x] Título, 4 autores, resumo (9 linhas) e abstract (8 linhas), ambos abaixo do limite de 10
- [x] Figuras vetoriais em TikZ: arquitetura (em uso) e sequência do Ricart-Agrawala (guardada)
- [x] Tabela 1 de resultados e §6 em prosa com a divisão de trabalho
- [x] 5 referências no formato SBC, todas citadas no texto (TSPLIB, ACO, Garcia-Molina, Lamport,
  Ricart-Agrawala)
- [x] `docs/evidencias/README.md` com o que coletar e como nomear

Pendente (o que ainda falta para a entrega):

- [x] **R1 — Autores:** Pedro Henrique Vila\c{c}a Valverde, Marcus Prado Silva,
  Gabriel Batista de Almeida e Eduardo Alves Salgado Lisboa Oliveira (4, dentro do 4–5 do enunciado).
- [ ] **R1b — Divisão em §6:** o texto atribui B (ACO/rodadas) a Gabriel e C (eleição/detector) a
  Eduardo, seguindo o `TODO.md`. **Confirmar** — é a única atribuição que não vem do git.
- [ ] **R2 — Data e formato:** confirmar no Canvas o prazo e se é upload de PDF.
- [x] **R3 — Texto das seis seções e do resumo/abstract:** escrito, com os números de 01/11.
- [ ] **R4 — Ensaio de 2 máquinas:** §4 hoje não menciona máquinas físicas (só a legenda da
  Tabela 1 registra que as medidas foram em 1 máquina com 4 contêineres). Se o ensaio for feito,
  acrescentar um parágrafo — mas o PDF está em 4 páginas exatas, então algo terá de sair.
- [x] **R5 — Fechamento técnico:** sem marcadores, sem `Overfull \hbox`, sem citação indefinida,
  4 páginas.
- [ ] **R6 — Reescrita (o item que decide a nota):** passar cada seção para as palavras de quem
  ficou responsável por ela, usando `relatorio/main.pdf` como esqueleto factual. O roteiro de cada
  seção (fatos + `arquivo:linha`) está no histórico do git (`git show 673da5c:relatorio/secoes/`).

> Lembrete do enunciado: IA só para revisar/corrigir texto. Texto praticamente gerado por IA é desconsiderado.

## 4. Higiene da entrega

- [ ] **Push da `main`:** `origin/main` está em `99d7525` (PR #3 do Marcus); os commits do
  relatório (`5bf8d60`, `b4809fc`, `673da5c` + merge `3eeaf76`) estão **só locais**. Falta `git push`.
- [x] Merge de `entrega1/validacoes` (`3290e09`), de `entrega1/pendencias` (`56e5955`),
  de `entrega1/implementacao` (`4591109`) e de `entrega1/pendencias-finais` (PR #3, `3eeaf76`).
- [ ] Apagar `entrega1/pendencias-finais` no GitHub (já mesclado via PR #3).
- [ ] Confirmar **mesmo commit** em todas as máquinas do ensaio (`git rev-parse --short HEAD`).
- [ ] `git status` limpo em cada máquina; `dependency-reduced-pom.xml` e `target/` já estão ignorados.
- [ ] Anotar versões usadas no relatório: `java -version`, `docker --version`, `docker compose version`.

## 5. Backlog — Entrega 2 (não mexer agora)

Do §6 de `docs/ARQUITETURA.md`:

- Tratamento de falhas: peer inacessível no `RequestCS` conta como permissão → risco de violação
  se ele só estava lento (detector por timeout não distingue lento de morto).
- Heartbeat só no líder: seguidor caído só é notado na próxima interação.
- Rodada perdida na troca de líder é pulada (novo líder segue de `lastRound + 1`).
- Réplicas da matriz sem versão/epoch: nó que volta recomeça da matriz inicial.
- Peer pulado no broadcast fica com réplica atrasada até a próxima atualização.
- 2PC na atualização de feromônio.
- Segurança: sem TLS o token trafega em claro e `sender_id` pode ser forjado → mTLS por nó.
- Análise de desempenho: 1 nó vs. 2, 3 e 4 nós.
- **Demonstração ao vivo:** cluster em 4 máquinas físicas, evidências completas de `docs/LAN.md` §3
  e roteiro de failover ensaiado — é requisito da apresentação de 10 min da Entrega 2.
