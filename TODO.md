# Plano — Entrega 1

Prazo: 2–4 semanas a partir de 12/10/2026 (confirmar a data exata no Canvas).
Cada pessoa é dona de um módulo e precisa conseguir explicá-lo na arguição.

## Divisão

| Pessoa | Módulo | Pacotes/arquivos |
|---|---|---|
| A | gRPC, infraestrutura, Docker, LAN | `rpc/`, `node.proto`, `NodeMain`, `Dockerfile`, `docker-compose.yml` |
| B | ACO / TSP | `aco/`, `round/` |
| C | Eleição de líder (Bully) + detector de falha | `election/` |
| D | Relógio de Lamport + Ricart-Agrawala | `clock/`, `mutex/` |

> Preencher os nomes: A = ____, B = ____, C = ____, D = ____

## Semana 1 — entender e fechar o núcleo
- [ ] Todos: ler `docs/ARQUITETURA.md`, rodar `mvn test` e `docker compose up`
- [x] D: implementar `RicartAgrawala.shouldDefer` até `RicartAgrawalaTest` passar
- [ ] A: validar build + compose em pelo menos 2 máquinas do grupo
- [ ] B: baixar `berlin52.tsp` (TSPLIB) para `data/` e testar com `TSP_FILE`
- [ ] C: revisar `BullyElection` e explicar o fluxo ao grupo

## Semana 2 — robustez e demo
- [ ] B: ajustar instância/parâmetros para cada rodada durar ~1 s (hoje, com 60 cidades, ~70 ms é rápido demais para demonstrar falha)
- [ ] C: roteiro de demo do failover (`docker compose stop node4` → N3 assume)
- [x] D: teste mostrando que sem mutex as réplicas divergem (argumento para o relatório) → `PheromoneMatrixTest`
- [ ] A: rodar 4 nós em 4 máquinas na LAN e registrar prints/logs

## Semana 3 — relatório (template SBC, até 4 páginas)
- [ ] Problema escolhido (B)
- [ ] Arquitetura: entidades, quem fala com quem, mensagens (A)
- [ ] Implementação dos requisitos: gRPC (A), Bully (C), Lamport + Ricart-Agrawala (D)
- [ ] Desafios encontrados (todos)
- [ ] Papel de cada aluno (todos)

> Lembrete do enunciado: IA só para revisar ou corrigir o texto. Texto praticamente gerado automaticamente é desconsiderado.

## Backlog (Entrega 2, não mexer agora)
- Tratamento de falhas (obrigatório): ver "Limitações conhecidas" em `docs/ARQUITETURA.md`
- Controle de réplicas da matriz (versão/epoch)
- 2PC na atualização de feromônio
- Análise de desempenho: 1 nó vs. 2, 3 e 4 nós
