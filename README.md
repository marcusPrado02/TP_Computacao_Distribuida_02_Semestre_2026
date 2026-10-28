# ACO distribuído para o TSP

Trabalho prático de Computação Distribuída (PUC Minas, 2026.2).
Colônia de Formigas (ACO) resolvendo o Caixeiro-Viajante em vários nós Java que se comunicam via gRPC.

**Entrega 1:** gRPC · eleição de líder (Bully) · relógio lógico (Lamport) · exclusão mútua (Ricart-Agrawala).

- Arquitetura e decisões: [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md)
- Enunciado do trabalho: [`docs/ENUNCIADO.md`](docs/ENUNCIADO.md)
- Plano e divisão de tarefas: [`TODO.md`](TODO.md)
- **Rodar em máquinas físicas (roteiro + checklist):** [`docs/LAN.md`](docs/LAN.md)

## Requisitos

- Java 21 e Maven 3.9+ (para rodar sem Docker)
- Docker + Docker Compose v2 (para rodar em contêineres)

## Build e testes (Docker)

Tudo roda em containers; não é preciso ter Java/Maven instalado no host.

```bash
docker build --target build -t aco-build .
docker run --rm aco-build mvn -B test   # 38 testes (unitários + integração), ~10 s
docker compose up --build               # cluster de 4 nós
```

O JAR `target/aco-node.jar` é gerado dentro do estágio `build` e copiado para a imagem final.
Se preferir sem Docker, `mvn test` e `mvn clean package` continuam funcionando (Java 21 + Maven 3.9+);
nesse caso, rode `mvn clean package` antes de executar o JAR para não empacotar classes velhas.

> Reiniciar um nó que era líder faz ele recomeçar da rodada 1 com a matriz inicial; veja
> "Limitações conhecidas" em `docs/ARQUITETURA.md` antes de fazer isso na demo.

## Token do cluster

Toda chamada gRPC é autenticada por um token compartilhado (`CLUSTER_TOKEN`), e o nó não sobe sem ele.
Gere um token por grupo e **não o versione**:

```bash
cp .env.example .env
sed -i "s/troque-este-token/$(openssl rand -hex 16)/" .env
```

Cada nó valida token e `PEERS` no startup: se um peer recusar a credencial, não reconhecer o
remetente ou tiver um conjunto de membros diferente, ele registra `ERRO FATAL DE CONFIGURAÇÃO` e
encerra com código 2, em vez de rodar um cluster paralelo em silêncio.

## Rodar com Docker Compose (4 nós)

```bash
docker compose up --build
# em outro terminal, derrubar o líder (maior id) para ver a reeleição:
docker compose stop node4
```

## Ensaio local que imita máquinas físicas (IPs fixos)

`docker-compose.lan.yml` usa **IPs estáticos** (`10.213.0.11`–`.14`) e `PEERS` por IP, sem DNS de
serviço — o mesmo caminho de rede da LAN. Serve para pré-validar antes de espalhar o cluster:

```bash
CLUSTER_TOKEN=$(openssl rand -hex 16) docker compose -f docker-compose.lan.yml up --build
docker compose -f docker-compose.lan.yml stop node4   # failover N4 -> N3
docker compose -f docker-compose.lan.yml down
```

Isto **não** substitui o teste em máquinas físicas exigido pelo enunciado.

## Rodar local, sem Docker (um terminal por nó)

```bash
export CLUSTER_TOKEN=<mesmo token em todos os nós>
NODE_ID=1 NODE_PORT=50051 PEERS="2=localhost:50052,3=localhost:50053" java -jar target/aco-node.jar
NODE_ID=2 NODE_PORT=50052 PEERS="1=localhost:50051,3=localhost:50053" java -jar target/aco-node.jar
NODE_ID=3 NODE_PORT=50053 PEERS="1=localhost:50051,2=localhost:50052" java -jar target/aco-node.jar
```

## Rodar na LAN (uma máquina por nó)

Em cada máquina, com os IPs do grupo (liberar a porta 50051 no firewall):

```bash
export CLUSTER_TOKEN=<mesmo token em todos os nós>
export RPC_TIMEOUT_MS=1500 BROADCAST_TIMEOUT_MS=5000 COORDINATOR_TIMEOUT_MS=8000
export START_DELAY_MS=2000 PEER_WAIT_MS=120000
NODE_ID=1 PEERS="2=192.168.0.12:50051,3=192.168.0.13:50051,4=192.168.0.14:50051" java -jar aco-node.jar
```

Todos os nós precisam usar os mesmos `TSP_SEED`/`TSP_CITIES` (ou o mesmo `TSP_FILE`).
O líder espera os peers responderem antes da 1ª rodada (`PEER_WAIT_MS`), então a ordem de subida
não importa. Checklist, evidências e diagnóstico: [`docs/LAN.md`](docs/LAN.md).
Os demais parâmetros estão em `docs/ARQUITETURA.md` §8.

## Lendo os logs

```
16:29:55.339 [N2][L=98] Rodada 1: ENTROU na seção crítica
             │    └── relógio de Lamport no momento do log
             └── id do nó
```

## Estrutura

```
src/main/proto/node.proto         contrato gRPC
src/main/java/br/pucminas/cd/aco/
  NodeMain.java                   ponto de entrada (lê env, trata desligamento)
  Node.java                       montagem e ciclo de vida do nó
  config/   rpc/                  configuração · cliente/servidor gRPC
  clock/                          relógio de Lamport
  election/                       Bully + monitor de líder
  mutex/                          Ricart-Agrawala
  aco/      round/                formigas, feromônio · rodadas (líder/trabalhador)
docker-compose.yml                4 nós por DNS de serviço (uma máquina)
docker-compose.lan.yml            4 nós por IP fixo (ensaio de LAN)
docs/LAN.md                       roteiro do teste em máquinas físicas
```
