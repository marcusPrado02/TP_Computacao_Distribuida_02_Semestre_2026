# Testes

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

## Como rodar

```bash
mvn clean test                              # todos (~10 s)
mvn test -Dtest=RicartAgrawalaSafetyTest    # uma classe
mvn test -Dtest='RicartAgrawalaTest#empate*' # um método (curinga)
```

Relatórios ficam em `target/surefire-reports/`. Se o VS Code estiver aberto e aparecer
`Unresolved compilation problem`, veja [guia-de-execucao.md](guia-de-execucao.md#solução-de-problemas).

## Pirâmide de testes do projeto

| Nível | Classes | Rede | Tempo | O que garante |
|---|---|---|---|---|
| Unitário | `LamportClockTest`, `NodeConfigTest`, `PheromoneMatrixTest`, `AntColonyTest` | nenhuma | ms | regras locais de cada componente |
| Unitário com *fake* | `BullyElectionTest`, `RicartAgrawalaTest` | transporte falso | < 1 s | regras dos algoritmos distribuídos, isoladas da rede |
| Propriedade / concorrência | `RicartAgrawalaSafetyTest` | rede simulada em memória | < 1 s | segurança da exclusão mútua sob intercalações aleatórias |
| Integração | `ClusterIntegrationTest` | gRPC real em `localhost` | ~4 s | o sistema inteiro funcionando junto |

### Por que *fakes* e interfaces de transporte

`BullyElection` e `RicartAgrawala` não conhecem gRPC: dependem de `ElectionTransport` e `MutexTransport`.
Em produção quem implementa é o `PeerNetwork`; nos testes, uma classe falsa de poucas linhas.
Isso permite testar o algoritmo controlando exatamente quem responde, quem está vivo e em que ordem as mensagens chegam,
coisa difícil de reproduzir com rede de verdade.

## O que cada teste prova

### `clock/LamportClockTest`
- `tickIncrementa`: evento local avança o relógio em 1.
- `recebimentoUsaMaximoMaisUm`: ao receber, `max(local, remoto) + 1`; mensagem "atrasada" (remoto menor) não faz o relógio voltar.

### `config/NodeConfigTest`
- `parsePeersMantemOrdemEIgnoraEspacos`: o formato `id=host:porta` aceita espaços e vírgula final.
- `parsePeersRejeitaEntradasInvalidas`: sem `=`, id não numérico e id repetido são rejeitados.
- `rejeitaConfiguracaoInconsistente`: nó listado como seu próprio peer, id 0 e `RHO = 0` falham na subida.
- `tspFileVazioEquivaleAAusente`: `TSP_FILE=""` (comum em Compose) é tratado como não definido.

### `aco/AntColonyTest`
- `encontraOtimoEmCirculo`: cidades num círculo têm ótimo conhecido (o polígono). Em 20 iterações o ACO precisa achá-lo.
  Valida juntos `AntColony`, `PheromoneMatrix` e `BestTracker` sem nenhuma parte distribuída.

### `aco/PheromoneMatrixTest`
- `flattenEReplaceSaoInversos`: a serialização usada no gRPC (row-major) não perde informação.
- `replaceRejeitaTamanhoErrado`: matriz de outra instância é recusada.
- `semExclusaoMutuaAsReplicasDivergemEPerdemDeposito`: **o argumento para usar exclusão mútua.**
  Dois nós fazem *ler → atualizar → propagar* ao mesmo tempo; cada um sobrescreve o outro (*lost update*);
  as réplicas divergem e nenhuma contém os dois depósitos.
- `comExclusaoMutuaAsReplicasConvergem`: serializando as atualizações (o que a SC faz), as réplicas ficam iguais
  ao resultado sequencial.

### `election/BullyElectionTest`
- `semMaioresVivosViraLider`: sem resposta de id maior, o nó se declara líder e anuncia COORDINATOR; nunca manda ELECTION para id menor.
- `maiorVivoRespondeEAnunciaCoordenador`: se um maior responde OK, o nó espera o COORDINATOR e aceita o maior como líder.
- `electionDeMenorRespondeOkEDisputa`: ao receber ELECTION de um menor, responde OK e inicia a própria eleição; de um maior, responde não.

### `mutex/RicartAgrawalaTest`
| Teste | Cenário | Esperado |
|---|---|---|
| `releasedRespondeNaHora` | não quero a SC | REPLY imediato |
| `heldAdiaEAoLiberarResponde` | estou na SC | adia; envia ao `release()` |
| `wantedComPedidoMaisAntigoRespondeNaHora` | quero, mas o outro pediu antes | REPLY imediato |
| `wantedComPedidoMaisNovoAdia` | quero e pedi antes | adia |
| `empateNoTimestampDesempataPeloId` | mesmo timestamp | id menor vence |
| `replyDePedidoAntigoNaoLiberaPedidoAtual` | REPLY com `request_ts` velho | ignorado; só o REPLY certo libera |
| `peerQueCaiDuranteEsperaDeixaDeSerAguardado` | peer some do conjunto de vivos | `acquire()` não trava |
| `releaseForaDaSecaoCriticaEhErro` | `release()` sem `acquire()` | `IllegalStateException` |

### `mutex/RicartAgrawalaSafetyTest`
4 instâncias de `RicartAgrawala` ligadas por uma rede em memória com atraso aleatório (0–2 ms por mensagem).
Cada nó entra 30 vezes na SC ao mesmo tempo que os outros. O teste mede quantos estão dentro simultaneamente.

- **Segurança:** o máximo observado tem que ser 1.
- **Vivacidade:** todas as 120 entradas acontecem dentro do timeout de 60 s (sem deadlock).

Foi validado por **mutação**: trocando a regra do estado WANTED por "sempre responde", o teste falha com
"expected 1 but was 4". Ou seja, ele realmente detecta violação de exclusão mútua.

### `ClusterIntegrationTest`
3 objetos `Node` na mesma JVM, cada um com servidor gRPC numa porta livre de `localhost`.

| Teste | Passos | Verifica |
|---|---|---|
| `elegeMaiorIdExecutaTodasAsRodadasEReplicasConvergem` | sobe 3 nós, 3 rodadas | todos elegem N3; todos veem a rodada 3; todos têm melhor rota; **as 3 matrizes de feromônio são idênticas** |
| `quedaDoLiderElegeOProximoMaiorId` | sobe 3 nós; derruba N3 | N1 e N2 passam a reconhecer N2 como líder (heartbeat → eleição) |
| `chamadaSemTokenOuDeRemetenteDesconhecidoEhRecusada` | chama `Heartbeat` direto | sem token: `UNAUTHENTICATED`; com token mas `sender_id` desconhecido: `PERMISSION_DENIED` |

Observação: os logs desse teste saem todos com o mesmo `[N..]`, porque `Log` é global ao processo
(em produção há um nó por processo).

## O que *não* está coberto (e por quê)

- **Docker Compose e LAN**: testados manualmente (ver [guia-de-execucao.md](guia-de-execucao.md)).
- **Violação de exclusão quando um peer está só lento**: é uma limitação conhecida do detector de falhas por timeout
  (ver `ARQUITETURA.md` §6), tema da Entrega 2.
- **Qualidade da solução do TSP em instâncias grandes**: não é requisito da Entrega 1; entra na análise de desempenho da Entrega 2.
