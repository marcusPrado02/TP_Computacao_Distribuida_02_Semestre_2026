# Pacote `config`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md).

---

## NodeConfig

Código: [`NodeConfig.java`](../../src/main/java/br/pucminas/cd/aco/config/NodeConfig.java)

### Responsabilidade
Guarda toda a configuração de um nó como um `record` imutável e valida os valores na construção.
O mesmo formato (variáveis de ambiente) serve para Docker Compose, terminal local e máquinas na LAN.

### Quem cria / quem usa
- Criado por `NodeConfig.fromEnv()` (produção, chamado em `NodeMain`) e diretamente pelo construtor nos testes.
- Lido por `Node`, `RoundCoordinator` (rodadas, atraso inicial, id) e `RoundWorker` (semente, formigas, ρ, id).

### Campos
| Campo | Variável | Padrão | Significado |
|---|---|---|---|
| `nodeId` | `NODE_ID` | obrigatória | Id único; o maior id vivo vence a eleição |
| `port` | `NODE_PORT` | `50051` | Porta do servidor gRPC |
| `peers` | `PEERS` | vazio | Mapa `id → host:porta` dos outros nós |
| `clusterToken` | `CLUSTER_TOKEN` | obrigatória | Segredo compartilhado do cluster |
| `tspFile` | `TSP_FILE` | `null` | Arquivo TSPLIB; tem prioridade sobre a instância aleatória |
| `tspCities` | `TSP_CITIES` | `60` | Nº de cidades da instância aleatória |
| `tspSeed` | `TSP_SEED` | `42` | Semente da instância aleatória e das formigas |
| `rounds` | `ROUNDS` | `30` | Total de rodadas |
| `ants` | `ANTS` | `40` | Formigas por nó por rodada |
| `alpha` | `ALPHA` | `1.0` | Peso do feromônio na escolha da próxima cidade |
| `beta` | `BETA` | `3.0` | Peso da distância (visibilidade) |
| `rho` | `RHO` | `0.1` | Taxa de evaporação |
| `startDelayMs` | `START_DELAY_MS` | `5000` | Espera do líder antes da primeira rodada |

### Métodos
| Método | O que faz |
|---|---|
| construtor compacto `NodeConfig { ... }` | Normaliza e valida (abaixo). Lança `IllegalArgumentException` com mensagem clara. |
| `static NodeConfig fromEnv()` | Lê as variáveis, converte tipos e chama o construtor. |
| `static Map<Integer,String> parsePeers(String raw)` | Converte `"2=host2:50051,3=host3:50051"` em mapa, mantendo a ordem. |

### Validações do construtor compacto
- `TSP_FILE` em branco vira `null` (o Compose pode passar `TSP_FILE=""`).
- `nodeId > 0` (o Bully compara ids) e ids dos peers `> 0`.
- `port` entre 1 e 65535.
- `peers` não pode conter o próprio `nodeId`.
- `clusterToken` não pode ser vazio.
- Sem `tspFile`, `tspCities >= 3`.
- `rounds >= 1`, `ants >= 1`, `alpha >= 0`, `beta >= 0`, `0 < rho <= 1`, `startDelayMs >= 0`.
- `peers` é copiado para um `LinkedHashMap` e embrulhado em `Collections.unmodifiableMap` (imutável, ordem preservada).

### Erros de parsing
- `parse(nome, valor, parser)` transforma `NumberFormatException` em
  `IllegalArgumentException("<NOME> com valor inválido: '<valor>'")`, então a mensagem diz **qual** variável está errada.
- `parsePeers` rejeita entrada sem `=`, host vazio, id não numérico e id repetido.
- Variável obrigatória ausente (`NODE_ID`, `CLUSTER_TOKEN`) lança `IllegalStateException`.
- Em todos os casos, `NodeMain` imprime a mensagem e sai com código 2.

### Thread-safety
Imutável (record com campos finais e mapa não modificável): pode ser compartilhado entre threads livremente.

### Detalhes e decisões
- **Por que validar no construtor e não em `fromEnv`?** Assim os testes, que usam o construtor direto, também
  ganham as validações. Um `NodeConfig` inválido simplesmente não existe.
- **Por que `LinkedHashMap`?** A ordem de `PEERS` é preservada nos logs (`peers = {2=..., 3=...}`), facilitando a leitura.
- **Todos os nós precisam da mesma instância do TSP.** Não há troca de instância pela rede: cada nó a gera
  localmente, então `TSP_SEED`/`TSP_CITIES` (ou `TSP_FILE`) precisam ser iguais em todos.
