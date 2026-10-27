# Pendências — Entrega 1

> Fotografia de **27/10/2026**, a partir do commit `176620a` (*Endurece o cluster para LAN…*).
> Este arquivo é a **lista de ação** (o que fazer, como verificar, quando está pronto).
> O plano por pessoa continua em [`TODO.md`](TODO.md); o roteiro do ensaio físico, em
> [`docs/LAN.md`](docs/LAN.md).

**Resumo:** 2 bloqueadores (1.1 e 1.2), 4 ajustes de demo/parâmetros, 5 itens de relatório.
Nada de Entrega 2 começa antes disso.

| # | Item | Dono (TODO.md) | Bloqueia a entrega? |
|---|---|---|---|
| 1.1 | Reconciliar `main` com `entrega1/validacoes` | A (todos revisam) | **Sim** |
| 1.2 | Cluster real em 4 máquinas + evidências | A | **Sim** |
| 2.1 | Rodada de ~1 s | B | Não (mas a demo fica ruim) |
| 2.2 | `berlin52.tsp` via `TSP_FILE` | B | Não |
| 2.3 | Roteiro de failover | C | Não |
| 2.4 | Revisar `BullyElection` | C | Não (arguição) |
| 3.x | Relatório SBC | todos | **Sim** |

## 0. O que já está pronto (não refazer)

- Núcleo distribuído: gRPC + streaming de feromônio, eleição Bully com detector de líder,
  relógio de Lamport, Ricart-Agrawala, formigas/rodadas, réplica da matriz de feromônio.
- **28 testes verdes** em 8 classes no último build registrado (`target/surefire-reports`,
  27/10 23:35): `RicartAgrawalaTest` (8), `RicartAgrawalaSafetyTest` (1), `NodeConfigTest` (6),
  `PheromoneMatrixTest` (4), `BullyElectionTest` (3), `ClusterIntegrationTest` (3),
  `LamportClockTest` (2), `AntColonyTest` (1).
- Ensaio local que imita LAN: `docker-compose.lan.yml` (IPs fixos), timeouts por env,
  barreira `PEER_WAIT_MS`, broadcast que pula peer morto.
- Docs: `docs/ARQUITETURA.md` (com limitações conhecidas §6), `docs/LAN.md`, `README.md`.

> Sem `mvn`/`java` no `PATH` desta máquina? Rode a suíte dentro da imagem de build:
> ```bash
> docker run --rm -v "$PWD":/app -w /app maven:3.9.9-eclipse-temurin-21 mvn -q -B test
> ```

## 1. Bloqueadores

### 1.1 Reconciliar `main` com `origin/entrega1/validacoes`

- [ ] **Fazer o merge e resolver os conflitos.**

Três commits de 20/10 (`e46a3a0`, `8f3ba03`, `ee09e5d`) **nunca entraram na `main`**. Eles contêm
trabalho que a entrega usa: parser TSPLIB em `TspInstance`, validação de token/membros no startup
(`HandshakeTest`), limite de mensagem gRPC, `docs/ENUNCIADO.md`, `docs/AUDITORIA.md`, testes novos
(`TspInstanceTest`, `RoundWorkerTest`). A `main` seguiu por outro caminho (`7d87660` da PR #2 →
`176620a`), então **as duas linhas divergiram** a partir de `ceea7b3`.

O merge conflita em 3 arquivos (confirmado com `git merge-tree --write-tree`):

```
CONFLICT (content): docs/ARQUITETURA.md
CONFLICT (content): src/main/java/br/pucminas/cd/aco/Node.java
CONFLICT (content): src/main/java/br/pucminas/cd/aco/rpc/PeerNetwork.java
```

```bash
git fetch origin
git switch main
git merge --no-ff origin/entrega1/validacoes
# resolver os conflitos preservando o endurecimento de LAN do 176620a
```

**Pronto quando:**
- `mvn -q test` verde (esperado: > 28 testes, com `TspInstanceTest`, `RoundWorkerTest`, `HandshakeTest`);
- `docker compose -f docker-compose.lan.yml up --build` sobe 4 nós, elege líder e completa rodadas;
- nenhum lado perdeu feature: checar que continuam existindo os timeouts por env, a barreira
  `PEER_WAIT_MS`, o skip de peer morto **e** a validação de token no startup.

> Risco: os dois lados mexeram em `Node`, `PeerNetwork` e eleição. Conflito resolvido "no braço"
> compila e ainda quebra o handshake em runtime — por isso o critério é teste de integração, não build.

### 1.2 Teste real: 4 máquinas físicas + evidências

- [ ] **Fazer o ensaio e arquivar as provas.**

Roteiro completo (pré-requisitos, comandos, tabela de IPs): [`docs/LAN.md`](docs/LAN.md) §2.
O ensaio em `docker-compose.lan.yml` **não substitui** este item — o enunciado exige nós físicos.

Preencher antes de subir (mesmo commit em todas as máquinas):

| Máquina | IP (`hostname -I`) | `NODE_ID` | Quem |
|---|---|---|---|
| A | | 1 | |
| B | | 2 | |
| C | | 3 | |
| D | | 4 | |

Evidências a salvar em `docs/evidencias/` (prints ou `.log`):

- [ ] `hostname -I` + `ss -ltn | grep 50051` em cada máquina (nó ouvindo).
- [ ] `Novo líder: N4` nas 4 máquinas no mesmo intervalo.
- [ ] `LÍDER: todos os 3 peers responderam; iniciando as rodadas` (barreira funcionou).
- [ ] `LÍDER: rodada 30 concluída; melhor global = ...` e `FIM: melhor rota = ...`.
- [ ] `[L=...]` de Lamport crescendo nos logs.
- [ ] **Failover real:** desligar/`Ctrl+C` na máquina do líder → `Líder N4 não responde há 3
  heartbeats` → `Novo líder: N3` nas 3 sobreviventes → rodadas seguem com `[1, 2, 3]`.

**Pronto quando:** as 6 evidências existem, com data e IP visíveis, e a suíte roda no commit usado
no ensaio (anotar o hash no relatório).

## 2. Ajustes técnicos antes da demo

### 2.1 Rodada de ~1 s (hoje ~70 ms)

- [ ] **Ler o tempo real e ajustar.**

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

### 2.2 Instância TSPLIB (`berlin52.tsp`)

- [ ] **Baixar e versionar a instância (ou documentar o download).**

`data/` só tem `README.md`. Baixar `berlin52.tsp` do TSPLIB, colocar em `data/` e conferir que
`TspInstance` lê `EDGE_WEIGHT_TYPE: EUC_2D` (o parser vem do branch `entrega1/validacoes`, item 1.1 —
fazer **depois** do merge, para não testar duas vezes).

Atenção em contêiner: o `Dockerfile` copia `data/` **no build**. Para trocar a instância sem rebuild,
monte o volume (`-v "$PWD/data:/app/data:ro"` no `docker run`, ou `volumes:` no compose) e aponte
`TSP_FILE=/app/data/berlin52.tsp`.

**Pronto quando:** os 4 nós logam a mesma distância para a melhor rota do arquivo, e o resultado é
comparável ao ótimo conhecido (7542 para `berlin52`, como sanity check, não como exigência).

### 2.3 Roteiro de demo do failover

- [ ] **Ensaiar e escrever em 5 linhas o que a banca vai ver.**

Cena: cluster rodando → `docker compose stop node4` (ou fechar a máquina) → em ~5 s os logs mostram
`Líder N4 não responde há 3 heartbeats` e `Novo líder: N3`; a rodada seguinte sai com `[1, 2, 3]`
e o relógio de Lamport continua crescendo. Ensaiar o timing para não ficar 30 s em silêncio na
apresentação (ver 2.1).

**Pronto quando:** o roteiro está no `docs/LAN.md` §3 ou no relatório, com comandos exatos e
o tempo esperado de cada passo.

### 2.4 Revisar `BullyElection` (arguição)

- [ ] **Ler `election/` inteiro e conseguir explicar sem tela.**

Pontos que a banca costuma cobrar: quem dispara a eleição, o que acontece com id maior/menor,
o que é `LeaderMonitor` (3 heartbeats) e por que eleição e detector são separados.

**Pronto quando:** C explica o fluxo em 2 minutos e responde "e se dois nós iniciarem eleição ao
mesmo tempo?".

## 3. Relatório (template SBC, até 4 páginas)

- [ ] Problema escolhido e justificativa (ACO/TSP) — B
- [ ] Arquitetura: entidades, quem fala com quem, mensagens gRPC — A
- [ ] gRPC + streaming de feromônio — A
- [ ] Bully + detector de falha (com o log do failover como figura) — C
- [ ] Lamport + Ricart-Agrawala, e o argumento do `PheromoneMatrixTest` (sem SC as réplicas divergem) — D
- [ ] Desafios encontrados (usar §6 de `docs/ARQUITETURA.md`: lento × morto, peer pulado, réplica sem versão)
- [ ] Papel de cada aluno (e quem fez o quê no código — bater com `git shortlog -sn`)
- [ ] Resultados: tabela do ensaio físico (nós, rodadas, tempo por rodada, melhor rota, tempo de failover)

> Lembrete do enunciado: IA só para revisar/corrigir texto. Texto praticamente gerado por IA é desconsiderado.

## 4. Higiene da entrega

- [ ] **Push da `main`:** hoje ela está **1 commit à frente de `origin/main`** (`176620a` não foi enviado).
  ```bash
  git push origin main
  ```
- [ ] Abrir PR do branch de trabalho (se a disciplina exigir) e conferir que o merge não deixou
  commit órfão do `entrega1/validacoes`.
- [ ] Confirmar **mesmo commit** em todas as máquinas do ensaio (`git rev-parse --short HEAD`).
- [ ] `git status` limpo nos 4 nós; `dependency-reduced-pom.xml` e `target/` já estão ignorados.
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
