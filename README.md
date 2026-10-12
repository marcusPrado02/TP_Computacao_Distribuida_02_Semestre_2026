# ACO distribuído para o TSP

Trabalho prático de Computação Distribuída (PUC Minas, 2026.2).
Colônia de Formigas (ACO) resolvendo o Caixeiro-Viajante em vários nós Java que se comunicam via gRPC.

**Entrega 1:** gRPC · eleição de líder (Bully) · relógio lógico (Lamport) · exclusão mútua (Ricart-Agrawala).

- Arquitetura e decisões: [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md)
- Plano e divisão de tarefas: [`TODO.md`](TODO.md)

## Requisitos

- Java 21 e Maven 3.9+ (para rodar sem Docker)
- Docker + Docker Compose v2 (para rodar em contêineres)

## Build e testes

```bash
mvn test              # testes unitários
mvn package           # gera target/aco-node.jar
```

## Rodar com Docker Compose (4 nós)

```bash
docker compose up --build
# em outro terminal, derrubar o líder (maior id) para ver a reeleição:
docker compose stop node4
```

## Rodar local, sem Docker (um terminal por nó)

```bash
NODE_ID=1 NODE_PORT=50051 PEERS="2=localhost:50052,3=localhost:50053" java -jar target/aco-node.jar
NODE_ID=2 NODE_PORT=50052 PEERS="1=localhost:50051,3=localhost:50053" java -jar target/aco-node.jar
NODE_ID=3 NODE_PORT=50053 PEERS="1=localhost:50051,2=localhost:50052" java -jar target/aco-node.jar
```

## Rodar na LAN (uma máquina por nó)

Em cada máquina, com os IPs do grupo (liberar a porta 50051 no firewall):

```bash
NODE_ID=1 PEERS="2=192.168.0.12:50051,3=192.168.0.13:50051,4=192.168.0.14:50051" java -jar aco-node.jar
```

Todos os nós precisam usar os mesmos `TSP_SEED`/`TSP_CITIES` (ou o mesmo `TSP_FILE`).
Os demais parâmetros estão em `docs/ARQUITETURA.md` §7.

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
  NodeMain.java                   montagem do nó
  config/   rpc/                  configuração · cliente/servidor gRPC
  clock/                          relógio de Lamport
  election/                       Bully + monitor de líder
  mutex/                          Ricart-Agrawala
  aco/      round/                formigas, feromônio · rodadas (líder/trabalhador)
```
