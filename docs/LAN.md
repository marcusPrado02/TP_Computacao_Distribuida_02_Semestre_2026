# Rodar em máquinas físicas (LAN) — roteiro e checklist

> O professor liberou testar localmente (inclusive Docker com rede e IPs fixos), **desde que a
> implementação funcione com múltiplos nós físicos**. Ou seja: o ensaio local é pré-checagem;
> a evidência que conta é o cluster rodando em máquinas diferentes.

## 1. Ensaio local que imita máquinas físicas

`docker-compose.lan.yml` sobe 4 contêineres com **IP fixo** (`10.213.0.11`–`.14`) e `PEERS`
apontando para **IPs**, nunca para nome de serviço. Assim o caminho exercitado é o mesmo da LAN
(socket TCP para `host:porta`), e não o DNS interno do compose padrão.

```bash
CLUSTER_TOKEN=$(openssl rand -hex 16) docker compose -f docker-compose.lan.yml up --build
docker compose -f docker-compose.lan.yml logs -f
```

O que deve aparecer (prova de que a rede e a barreira funcionam):

```
[N4][L=0] Rede: rpc=1500 ms, broadcast=5000 ms, eleição=8000 ms, espera de peers=120000 ms
[N1][L=19] Novo líder: N4
[N4][L=78] LÍDER: todos os 3 peers responderam; iniciando as rodadas
[N4] LÍDER: iniciando rodada 1/30 com nós [1, 2, 3, 4]
```

Failover no ensaio (dura ~5 s: 3 heartbeats de 1 s + eleição):

```bash
docker compose -f docker-compose.lan.yml stop node4
# esperado: "Líder N4 não responde há 3 heartbeats" e "Novo líder: N3" em N1/N2/N3,
# depois "LÍDER: iniciando rodada N/30 com nós [1, 2, 3]"
docker compose -f docker-compose.lan.yml down
```

> **Isto não substitui o teste físico.** Ele pega timeout curto, nó que sobe depois dos outros e
> configuração errada de `PEERS` — mas loopback de bridge ainda é mais rápido e mais estável que
> Wi-Fi/roteador da faculdade.

## 2. Teste real: uma máquina por nó

Pré-requisitos em **todas** as máquinas:

- Java 21 **ou** Docker (para rodar a imagem `aco-node`).
- O mesmo JAR/imagem (mesmo commit).
- Porta **50051/TCP** liberada: `sudo ufw allow 50051/tcp` (ou equivalente).
- As máquinas na **mesma sub-rede**, sem isolamento de clientes (Wi-Fi de visitante costuma
  bloquear tráfego entre hosts).
- Mesmo `CLUSTER_TOKEN`, mesmo `TSP_FILE` (ou `TSP_CITIES`/`TSP_SEED`).

Antes de subir, anote o IP de cada máquina (`hostname -I`) e monte a tabela:

| Máquina | IP | `NODE_ID` |
|---|---|---|
| notebook A | 192.168.0.11 | 1 |
| notebook B | 192.168.0.12 | 2 |
| notebook C | 192.168.0.13 | 3 |
| notebook D | 192.168.0.14 | 4 |

Em cada máquina, exporte `PEERS` com **os outros três** IPs (nunca o próprio):

```bash
export CLUSTER_TOKEN='<mesmo token em todas>'
export NODE_ID=1
export PEERS="2=192.168.0.12:50051,3=192.168.0.13:50051,4=192.168.0.14:50051"
export TSP_CITIES=60 TSP_SEED=42 ROUNDS=30 ANTS=40
# tempos de LAN (o padrão de 500 ms é para loopback):
export RPC_TIMEOUT_MS=1500 BROADCAST_TIMEOUT_MS=5000 COORDINATOR_TIMEOUT_MS=8000
export START_DELAY_MS=2000 PEER_WAIT_MS=120000
```

Rodando o JAR direto:

```bash
java -jar target/aco-node.jar
```

Rodando em contêiner com a rede do host (Linux; é o mais próximo de rodar “na máquina”):

```bash
docker build -t aco-node .
docker run --rm --network host \
  -e CLUSTER_TOKEN -e NODE_ID -e PEERS -e TSP_CITIES -e TSP_SEED -e ROUNDS -e ANTS \
  -e RPC_TIMEOUT_MS -e BROADCAST_TIMEOUT_MS -e COORDINATOR_TIMEOUT_MS \
  -e START_DELAY_MS -e PEER_WAIT_MS aco-node
```

Ordem de subida não importa: o líder eleito espera até `PEER_WAIT_MS` todos os peers responderem
um heartbeat antes da **primeira** rodada (`LÍDER: aguardando peers [...] subirem`).

## 3. Evidências para a entrega

- Em cada máquina, `hostname -I` e `ss -ltn | grep 50051` mostrando o nó ouvindo (print).
- Log das 4 máquinas com `Novo líder: N4` no mesmo intervalo de tempo.
- `LÍDER: todos os 3 peers responderam; iniciando as rodadas` (barreira).
- Rodadas concluídas: `LÍDER: rodada 30 concluída; melhor global = ...` e `FIM: melhor rota = ...`.
- Failover real: `Ctrl+C`/desligar a máquina do líder → `Líder N4 não responde há 3 heartbeats`
  → `Novo líder: N3` nas três sobreviventes.
- Print dos relógios de Lamport crescendo nos logs (`[L=...]`).

## 4. Diagnóstico rápido

| Sintoma | Causa provável | Correção |
|---|---|---|
| Cada nó diz `Novo líder: N<ele mesmo>` e ninguém converge | `PEERS` com IP errado, ou porta bloqueada | conferir `hostname -I`, `PEERS` e `ufw status` |
| `LÍDER: aguardando peers [...] subirem` até estourar `PEER_WAIT_MS` | firewall/`PEERS` errado, ou peer realmente ausente | liberar 50051, corrigir `PEERS` |
| `UNAUTHENTICATED` nos logs | `CLUSTER_TOKEN` diferente entre nós | padronizar o token |
| `PERMISSION_DENIED` | `NODE_ID` não bate com o id usado pelos peers em `PEERS` | conferir a tabela de ids |
| Eleição sem nó ter caído | `RPC_TIMEOUT_MS` curto demais para o Wi-Fi | subir para 1500–3000 ms |
| Rodada demora ~5 s depois de uma queda | peer morto ainda sendo aguardado no broadcast | já mitigado (peer morto é pulado); conferir logs |
| Nós não se veem, mas `ping` funciona | rede de visitantes / isolamento do roteador | usar outra rede ou cabo/roteador próprio |

## 5. Parâmetros de rede (resumo)

| Variável | Loopback/compose | LAN sugerida | Efeito |
|---|---|---|---|
| `RPC_TIMEOUT_MS` | 500 | 1500 | deadline das chamadas curtas (heartbeat, eleição, REQUEST/REPLY) |
| `BROADCAST_TIMEOUT_MS` | 2000 | 5000 | deadline do broadcast da matriz de feromônio |
| `COORDINATOR_TIMEOUT_MS` | 3000 | 8000 | espera pelo `COORDINATOR` antes de reiniciar a eleição |
| `PEER_WAIT_MS` | 0 (desligado) | 120000 | barreira: líder espera todos os peers antes da 1ª rodada |
| `START_DELAY_MS` | 5000 | 2000 | espera do líder antes de iniciar (some quando a barreira está ligada) |
