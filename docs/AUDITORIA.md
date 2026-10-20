# Auditoria da Entrega 1

> Documento interno de engenharia (não é o texto do relatório). Registra o que foi verificado,
> o que foi corrigido e o que continua em aberto. Data: 20/10/2026.

## Escopo e método

- Revisão de todo o código de produção e dos testes (protocolos, relógio, RPC, ACO, rodadas,
  configuração e ciclo de vida).
- Suíte completa em container (`mvn test`): 37 testes, executada 5× seguidas sem falha.
- Cluster real de 4 nós via Docker Compose (60 cidades, 40 formigas), com parada de líder,
  parada de seguidor e reinício de líder.
- Programas de scratch fora do repositório para forçar cenários que os testes não cobriam
  (REPLY perdido, peer que morre depois do REQUEST, peer recuperado, token divergente, TSPLIB
  malformado, mensagem de 4,8 MB).
- Cada correção relevante ganhou um teste de regressão no repositório.

## Achados

| ID | Severidade | Achado | Evidência | Situação |
|---|---|---|---|---|
| F1 | Crítica | Um REQUEST que falha conta como permissão. Com o peer vivo (só lento), os dois entram na SC. | Dois `RicartAgrawala` reais, um REQUEST devolvendo `false` com o outro já na SC: ambos dentro. | **Não corrigido** (limitação fundamental; ver abaixo). |
| F2 | Alta | Peer que recebe o REQUEST e morre antes do REPLY (ou REPLY perdido) deixava o `acquire()` esperando para sempre. | `acquire()` ainda em `WANTED` após 3 s. | Corrigido: REQUEST reenviado a cada 500 ms + teste `peerQueCaiDepoisDeReceberRequestNaoTrava` e `pedidoReenviadoRecuperaReplyPerdido`. |
| F3 | Alta | Peer marcado inacessível nunca era recontatado, mesmo depois de voltar. | 2ª entrada não enviava REQUEST ao peer recuperado. | Corrigido: pedido vai a todos os peers + quarentena de 2 s + teste `peerQueVoltaEhRecontatadoNaProximaEntrada`. |
| F4 | Alta | Morte de **seguidor** custava 30 s (timeout da rodada) e depois ~4–6 s por rodada, porque o líder esperava o `RoundDone` e o broadcast pagava 2 s pelo peer morto. | Compose: rodada 207 encerrada por timeout 30 s depois; rodadas seguintes a ~6 s. | Corrigido: probe por heartbeat no `awaitRound`, quarentena nos broadcasts e no `sendRequest`; teste `quedaDeUmSeguidorNaoTravaNemAtrasaAsRodadas`. Revalidado no Compose: 4000 rodadas sem timeout e 600 rodadas com seguidor morto no meio, todos os sobreviventes concluíram. |
| F5 | Alta | Após reiniciar o líder, seguidores enviavam `RoundDone` ao líder **eleito antigo**; o novo líder esperava 30 s por rodada. | Compose: N4 reiniciou, N1/N3 ficaram com N3 e N4 esperava. | Corrigido: `RoundDone` volta para quem enviou o `StartRound`; líder reanuncia `Coordinator` a cada 1 s. Revalidado no Compose. |
| F6 | Média | Líder reiniciado volta a `lastRound = 0`, recomeça em 1 e retransmite a matriz inicial (progresso das réplicas é perdido). | Compose: N4 reiniciou e executou 1/4000 novamente; seguidores não refizeram a SC. | **Mitigado**: rodada repetida não reaplica feromônio e responde (`RoundWorkerTest`). Correção completa (transferência de estado/epoch) fica para a Entrega 2. |
| F7 | Alta | TSPLIB: `DISPLAY_DATA_SECTION` causava `ArrayIndexOutOfBoundsException`; arquivo vazio estourava no startup; 2 cidades eram aceitas. | Execução real do JAR com os três arquivos. | Corrigido: parser para em outra `_SECTION`, valida a seção, a linha e o mínimo de 3 cidades; `NodeMain` imprime erro claro. Testes em `TspInstanceTest`. |
| F8 | Média | Limite padrão de 4 MiB do gRPC: matriz de ~725+ cidades falhava com `RESOURCE_EXHAUSTED` (e o peer era marcado como morto). | Mensagem de 4,8 MB rejeitada com "exceeds maximum size 4194304". | Corrigido: 64 MiB no servidor e nos stubs (~2900 cidades). Mensagem de 4,8 MB agora chega ao handler. |
| F9 | Média | `CLUSTER_TOKEN` diferente: cada nó se elege líder e roda o ACO sozinho; só aparece `inacessível (UNAUTHENTICATED)`. | Dois nós com tokens distintos: ambos líderes, ambos terminaram suas rodadas. | **Não corrigido**: logs agora distinguem `UNAUTHENTICATED` (token) de `PERMISSION_DENIED` (PEERS). Recomendado handshake de startup que falhe rápido. |
| F10 | Baixa | Sem `.dockerignore`, o contexto enviava `.git`, `target` e potencialmente `.env` ao daemon. | Contexto de build caiu para ~71 kB após o arquivo. | Corrigido: `.dockerignore` (inclui `.env`). |
| F11 | Baixa | Ordem de `close()` derrubava módulos antes do servidor; `sendReply` podia lançar `RejectedExecutionException` durante o shutdown. | Revisão + cenário de parada. | Corrigido: servidor para primeiro, espera handlers; `sendReply` ignora rejeição no shutdown. |
| F12 | Baixa | `Coordinator` aceitava `leader_id` diferente do remetente. | Revisão. | Corrigido: `NodeServiceImpl` ignora anúncio inconsistente. |
| F13 | Média | Heartbeat de 500 ms podia gerar falso positivo sob carga (eleição desnecessária). | Compose com 4000 rodadas: líder trocou N4→N3→N4 em segundos. | Mitigado: timeout de 1 s e 5 faltas (~5 s). Cenário padrão de 30 rodadas não apresentou troca. |

## O que continua em aberto (e por quê)

1. **Exclusão mútua não é garantida sob falso positivo.** É impossível distinguir "lento" de "morto"
   de forma assíncrona. A política atual (falha = permissão) prioriza liveness. Para garantir
   segurança seria preciso quórum/membership com visão consistente ou consenso (Entrega 2).
2. **Peer alcançável que nunca responde** ainda trava o `acquire()` (não há timeout global). Um
   timeout global apenas troca travamento por risco de violação.
3. **Réplicas sem versão/epoch**: update atrasado pode aplicar matriz antiga; nó que volta pode
   sobrescrever a matriz de todos. É o item mais importante de controle de réplicas.
4. **Catch-up de líder**: reinício retrocede rodadas e reseta a matriz. O `RoundWorker` evita o
   retrabalho nos seguidores, mas o líder ainda recomeça.
5. **Split-brain por configuração divergente** (token/PEERS). Um handshake no startup resolveria.
6. **Sem TLS**, sem persistência, sem chunking de matriz acima de ~2900 cidades.

## Como reproduzir a verificação

```bash
docker build --target build -t aco-build .
docker run --rm aco-build mvn -B test

# cluster real (o .env precisa existir com CLUSTER_TOKEN)
docker compose up --build
docker compose logs -f
docker compose stop node2       # seguidor: rodadas continuam
docker compose stop node4       # líder: novo líder em ~5 s
docker compose down
```

Observações de medição (host desta auditoria, 4 containers):

- 30 rodadas, 60 cidades: todas concluídas em ~0,8 s; réplicas convergem (teste de integração).
- Parada de seguidor no meio de 600 rodadas: nenhum timeout, sobreviventes concluíram as 600.
- Parada de líder: novo líder em ~3,5–5 s; reinício do líder: converge para o maior id em ~1 s.
