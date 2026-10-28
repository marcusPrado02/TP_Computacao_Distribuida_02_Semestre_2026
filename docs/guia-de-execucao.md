# Guia de execução e demonstração

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

## 0. Atalho: o script `./tp`

Quase tudo deste guia pode ser feito pelo script `./tp` (código em [`scripts/tp.sh`](../scripts/tp.sh)).
Sem argumentos ele abre um **menu interativo**, que pergunta os parâmetros e sugere valores padrão. Com argumentos, roda direto, então serve para automatizar.

| Comando | O que faz |
|---|---|
| `./tp build` / `./tp test [Classe[#metodo]]` | compila / roda testes com resumo |
| `./tp up [-n nós] [-r rodadas] [-a formigas] [-c cidades] [-s semente] [-f arquivo.tsp] [-d ms] [-p porta]` | sobe N nós locais em background (portas 50051, 50052, ...) |
| `./tp status` | tabela: vivo/parado, pid, porta, líder visto, última rodada, relógio de Lamport |
| `./tp kill <id> [TERM]` | derruba um nó; padrão `SIGKILL` simula crash (sem shutdown hook) |
| `./tp start <id>` | religa o nó com os mesmos parâmetros |
| `./tp logs [id] [filtro] [-f]` | logs ordenados por hora; filtros `cs`, `eleicao`, `rodada`, `rede` ou uma regex |
| `./tp timeline [filtro]` | todos os nós numa só linha do tempo, **ordenada pelo relógio de Lamport** |
| `./tp check` | verifica nos logs que nunca houve dois nós na seção crítica |
| `./tp demo failover [nós]` / `./tp demo mutex [nós]` | demonstrações roteirizadas (seção 4) |
| `./tp docker up\|down\|ps\|logs\|stop N\|kill N\|start N` | atalhos do Docker Compose (cria `.env` se faltar) |
| `./tp lan <meu_id> <ip1> <ip2> ...` | imprime o comando exato para rodar o nó numa máquina da LAN |
| `./tp down` / `./tp clean` | derruba o cluster / apaga `.run/` |

O estado do cluster local (pids, parâmetros, logs `n<id>.log`) fica em `.run/`, que não é versionado.
O script recompila sozinho se o JAR estiver mais velho que o código.

### Como o `./tp check` funciona

1. Junta as linhas `ENTROU`/`SAIU` de todos os nós.
2. Ordena pelo **relógio de Lamport** (`[L=...]`), não pela hora. Se B entrou depois que A saiu, existe a cadeia
   causal `SAIU(A) → REPLY → ENTROU(B)`, logo `L(SAIU_A) < L(ENTROU_B)`. Isso vale até com nós em máquinas de
   relógios físicos diferentes, onde ordenar por hora daria resultados errados.
3. Percorre a sequência: um `ENTROU` enquanto outro nó está dentro é **violação** (sai com código 1).
4. Nó derrubado pelo `./tp kill` ganha um marcador `CAIU` no log. Um `ENTROU` seguido de `CAIU` fica de fora e
   vira nota: os outros entram porque *detectaram a falha por timeout*, e não há mensagem do nó morto que
   ligue os eventos causalmente.

A verificação foi validada com logs sintéticos com sobreposição (detectada) e com dezenas de quedas do líder
(nenhuma violação).

## 1. Preparar

```bash
mvn clean package                 # gera target/aco-node.jar e roda os testes
cp .env.example .env              # só para Docker Compose
sed -i "s/troque-este-token/$(openssl rand -hex 16)/" .env
```

Todos os nós precisam ter **o mesmo** `CLUSTER_TOKEN` e **a mesma instância** do TSP
(mesmos `TSP_CITIES` + `TSP_SEED`, ou o mesmo `TSP_FILE`). Se a instância for diferente, as matrizes
de feromônio têm tamanhos diferentes e o `UpdatePheromone` é rejeitado.

## 2. Três formas de rodar

| Forma | Quando usar | Comando |
|---|---|---|
| Docker Compose | desenvolvimento e demo de falha numa máquina | `docker compose up --build` |
| Vários terminais | depurar sem Docker | ver abaixo |
| LAN | demo com uma máquina por nó | ver abaixo |

### Vários terminais (mesma máquina)

```bash
export CLUSTER_TOKEN=abc ROUNDS=10
NODE_ID=1 NODE_PORT=50051 PEERS="2=localhost:50052,3=localhost:50053" java -jar target/aco-node.jar
NODE_ID=2 NODE_PORT=50052 PEERS="1=localhost:50051,3=localhost:50053" java -jar target/aco-node.jar
NODE_ID=3 NODE_PORT=50053 PEERS="1=localhost:50051,2=localhost:50052" java -jar target/aco-node.jar
```

### LAN (uma máquina por nó)

1. Descubra o IP de cada máquina (`ip addr` / `ipconfig`) e libere a porta 50051 no firewall.
2. Em cada máquina, com o mesmo token:
   ```bash
   export CLUSTER_TOKEN=<token do grupo>
   NODE_ID=1 PEERS="2=192.168.0.12:50051,3=192.168.0.13:50051,4=192.168.0.14:50051" java -jar aco-node.jar
   ```
3. Suba os nós em qualquer ordem: quem chega depois com id maior assume a liderança (Bully).

## 3. Lendo os logs

```
16:29:55.339 [N2][L=98] Rodada 1: ENTROU na seção crítica
│            │    │     └── mensagem
│            │    └── relógio de Lamport neste instante
│            └── id do nó que escreveu
└── hora local (só para humanos; a ordem causal é dada por L)
```

| Mensagem | Módulo | Significado |
|---|---|---|
| `Eleição iniciada` | Bully | o nó mandou ELECTION aos de id maior |
| `Novo líder: N4` | Bully | recebeu (ou enviou) COORDINATOR |
| `Nó 2 se declarou líder, mas tenho id maior; contestando` | Bully | regra do "valentão" |
| `Líder N4 não responde há 5 heartbeats; iniciando eleição` | Monitor | detector de falha disparou |
| `LÍDER: iniciando rodada k/R com nós [...]` | Coordenador | StartRound enviado |
| `Rodada k: melhor local = X` | Trabalhador | formigas do nó terminaram |
| `Rodada k: ENTROU / SAIU da seção crítica` | Ricart-Agrawala | entrada e saída da SC |
| `LÍDER: rodada k concluída; melhor global = X` | Coordenador | todos os vivos mandaram RoundDone |
| `N3 inacessível (UNAVAILABLE)` / `N3 voltou a responder` | Rede | mudança na visão de vivos |
| `FIM: melhor rota = X [...]` | Coordenador | todas as rodadas concluídas |

**Como mostrar a exclusão mútua nos logs:** junte os logs dos nós e ordene por hora.
Entre um `ENTROU` e o `SAIU` seguinte nunca aparece outro `ENTROU`.

```bash
docker compose logs --no-log-prefix | grep -E "ENTROU|SAIU" | sort
```

## 4. Roteiros de demonstração

> **Roteiros automáticos:** `./tp demo failover` faz a Demo 2 inteira (elege, derruba o líder, mede o tempo
> de reeleição, religa o antigo líder e roda o `check`). `./tp demo mutex` faz a Demo 3 e mostra a seção crítica
> em ordem de Lamport.

### Demo 1: eleição na subida
1. `docker compose up --build`
2. Mostre `Novo líder: N4` em todos os nós: o maior id vence.

### Demo 2: queda do líder (failover)
1. Com o sistema rodando: `docker compose stop node4`
2. Em ~7,5 s na LAN (5 heartbeats de 1,5 s), os seguidores logam `Líder N4 não responde há 5 heartbeats`.
   No compose padrão (heartbeat de 1 s) são ~5 s.
3. Nova eleição: `Novo líder: N3`, que continua da última rodada vista.
4. Opcional: `docker compose start node4`. Ele sobe, faz eleição, vence e N3 aceita.

### Demo 3: exclusão mútua com relógio de Lamport
1. Use poucas cidades e muitas formigas para que as rodadas fiquem lentas e legíveis
   (ou aumente `ROUNDS` e `ANTS` no `docker-compose.yml`).
2. Mostre dois nós pedindo a SC: quem tem o menor `(L, id)` entra primeiro; o outro só entra depois do `SAIU`.

### Demo 4: autenticação
Suba um nó com outro `CLUSTER_TOKEN`: as chamadas dele são recusadas com `UNAUTHENTICATED` e ele aparece
como inacessível para os outros.

## 5. Parâmetros

Ver a tabela completa em [`ARQUITETURA.md` §8](ARQUITETURA.md#8-parâmetros). Os mais úteis na demo:

| Variável | Efeito |
|---|---|
| `ROUNDS` | quantas rodadas o líder dispara |
| `ANTS` | formigas por nó por rodada (mais = rodada mais lenta e melhor) |
| `TSP_CITIES` / `TSP_FILE` | tamanho da instância (mais cidades = rodada mais lenta) |
| `START_DELAY_MS` | espera do líder antes da 1ª rodada (dá tempo dos nós subirem) |

## 6. Solução de problemas

| Sintoma | Causa provável | Solução |
|---|---|---|
| `Configuração inválida: ...` e saída com código 2 | variável faltando ou incoerente | leia a mensagem; ver `NodeConfig` |
| `Unresolved compilation problem` ao rodar o JAR ou nos testes | a extensão Java do VS Code compilou em `target/` | `Ctrl+Shift+P` → *Java: Clean Java Language Server Workspace*, depois `mvn clean package` |
| Classes `NodeServiceGrpc`, `Header`... em vermelho na IDE | código gerado do `.proto` não indexado | `mvn generate-sources` e *Maven: Reload Projects* |
| Todos os peers `UNAVAILABLE` | firewall, IP errado ou porta diferente | `nc -zv <ip> 50051` a partir da outra máquina |
| Peers `UNAUTHENTICATED` | tokens diferentes | use o mesmo `CLUSTER_TOKEN` em todos |
| `Matriz com tamanho X, esperado Y` | instâncias TSP diferentes | mesmos `TSP_CITIES`/`TSP_SEED` ou `TSP_FILE` |
| Rodadas rápidas demais para ver algo | instância pequena | aumente `ANTS`/`TSP_CITIES` ou use `berlin52.tsp` |
