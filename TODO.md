# Plano — Entrega 1

Prazo: 2–4 semanas a partir de 12/10/2026 (**confirmar a data exata no Canvas** — item R2 de
`PENDENCIAS.md`). Cada pessoa é dona de um módulo e precisa conseguir explicá-lo na arguição;
o material de estudo e as perguntas de cada fatia estão em [`docs/ARGUICAO.md`](docs/ARGUICAO.md).

## Divisão

| Pessoa | Módulo | Pacotes/arquivos | Entregável verificável (Entrega 1) |
|---|---|---|---|
| A — Pedro Henrique Vilaça Valverde | gRPC, infraestrutura, Docker, LAN | `rpc/`, `node.proto`, `NodeMain`, `Dockerfile`, `docker-compose*.yml` | ensaio em 2 máquinas + logs em `docs/evidencias/` |
| B — Gabriel Batista de Almeida | ACO / TSP e rodadas | `aco/`, `round/` | remedir berlin52 e fechar a Tabela 1 do relatório |
| C — Eduardo Alves Salgado Lisboa Oliveira | Eleição de líder (Bully) + detector | `election/` | rodar o failover cronometrado (`docs/LAN.md` §3.1) e registrar os tempos |
| D — Marcus Prado Silva | Relógio de Lamport + Ricart-Agrawala | `clock/`, `mutex/` | `./tp check` + `./tp timeline cs` com a saída arquivada |

> O relatório (`relatorio/secoes/06-papeis.tex`) diz que cada aluno é **responsável** pelo seu módulo
> — responsável pela demonstração e pela explicação. Quem não escreveu o código diz "acompanhei e
> reproduzi", não "implementei" (regra em `docs/ARGUICAO.md`).

## Semana 1 — entender e fechar o núcleo
- [ ] Todos: ler `docs/ARQUITETURA.md`, rodar testes e cluster **só com Docker** (sem Java/Maven no host):
      `docker build --target build -t aco-build .` → `docker run --rm aco-build mvn -B test` → `docker compose up --build`
- [x] D: implementar `RicartAgrawala.shouldDefer` até `RicartAgrawalaTest` passar
- [x] A: ensaio local com IPs fixos (`docker-compose.lan.yml`), barreira de subida (`PEER_WAIT_MS`)
  e timeouts de LAN
- [ ] A: validar build + compose em pelo menos 2 máquinas do grupo — roteiro em `docs/LAN.md` §2
- [x] B: baixar `berlin52.tsp` (TSPLIB) para `data/` e testar com `TSP_FILE` (chega ao ótimo 7542)
- [x] C: revisar `BullyElection` e explicar o fluxo ao grupo
- [ ] Todos: rodar `./tp demo failover` e `./tp demo mutex` pelo menos uma vez até o fim

## Semana 2 — robustez e demo
- [x] B: ajustar instância/parâmetros para cada rodada durar ~1 s → `ITERATIONS_PER_ROUND=1000` (~0,94 s; remedir na LAN)
- [x] C: roteiro de demo do failover (`docker compose stop node4` → N3 assume) → `docs/LAN.md` §3.1
- [x] D: teste mostrando que sem mutex as réplicas divergem (argumento para o relatório) → `PheromoneMatrixTest`
- [ ] A: rodar 4 nós em 4 máquinas na LAN e registrar prints/logs — evidências listadas em `docs/LAN.md` §3

## Semana 3 — relatório (template SBC, até 4 páginas)
- [x] Problema escolhido (B)
- [x] Arquitetura: entidades, quem fala com quem, conteúdo das mensagens (A)
- [x] Implementação dos requisitos: gRPC (A), Bully (C), Lamport + Ricart-Agrawala (D)
- [x] Desafios encontrados (todos)
- [x] Papel de cada aluno (todos)
- [x] PDF com 4 páginas, sem marcadores, sem `Overfull`, sem citação indefinida (`relatorio/main.pdf`)
- [ ] **R6 — reescrever o texto com palavras próprias** (o item que decide a nota; ver o aviso em
  `PENDENCIAS.md` §3). Hoje o texto é de IA, por decisão registrada do grupo.

## Entregáveis por pessoa, antes da entrega

Nenhum é opcional: cada linha do §6 do relatório precisa de um artefato em `docs/evidencias/`.

| Quem | O que entregar | Como verificar |
|---|---|---|
| Pedro (A) | logs dos 2 nós (mesmo líder, mesma melhor rota) + tempo de subida | `docs/LAN.md` §2; arquivo em `docs/evidencias/` |
| Gabriel (B) | saída de `./tp up -f data/berlin52.tsp -i 1000` com `FIM: melhor rota = 7542` e os tempos por rodada | preencher a Tabela 1 com os números dele |
| Eduardo (C) | `./tp demo failover` com os tempos (`Novo líder: N3 em ~Xs`) | comparar com `docs/LAN.md` §3.1 (~2 s / ~8,5 s / ~10 s) |
| Marcus (D) | `./tp check` (0 violações) e `./tp timeline cs` (ordem de Lamport) | anexar a saída em `docs/evidencias/` |

## Arguição (ver `docs/ARGUICAO.md`)

- [ ] Cada um explica o seu módulo em **2 minutos, sem anotações**
- [ ] Cada um responde as **3 perguntas** da sua fatia
- [ ] Cada um roda **1 comando** que mostra o módulo funcionando, do zero
- [ ] Todos respondem as 8 perguntas gerais (problema, arquitetura, requisitos, limitação)
- [ ] Apresentação de 10 min ensaiada com o cluster já rodando

> Lembrete do enunciado: IA só para revisar ou corrigir o texto. Texto praticamente gerado automaticamente é desconsiderado.

## Backlog (Entrega 2, não mexer agora)
- Tratamento de falhas (obrigatório): ver "Limitações conhecidas" em `docs/ARQUITETURA.md`
- Controle de réplicas da matriz (versão/epoch)
- 2PC na atualização de feromônio
- Análise de desempenho: 1 nó vs. 2, 3 e 4 nós
