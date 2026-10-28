# Evidências do ensaio

Pasta para guardar as provas de execução que sustentam a seção de resultados do relatório.
**Não** vai para o PDF: o relatório cita os números, não as capturas.

## O que coletar (ensaio mínimo, 2 máquinas)

Roteiro completo em [`../LAN.md`](../LAN.md) §2. Salvar cada item com data no nome do arquivo:

| Arquivo | Como obter | Para que serve |
|---|---|---|
| `AAAA-MM-DD-hostA-hostname.txt` | `hostname -I; ss -ltn \| grep 50051` | prova de que o nó está ouvindo na interface da LAN |
| `AAAA-MM-DD-hostA.log` / `hostB.log` | `java -jar target/aco-node.jar 2>&1 \| tee ...` | `Novo líder`, barreira, rodadas, `FIM`, failover |
| `AAAA-MM-DD-failover.txt` | anotar hora do `Ctrl+C` e hora do `Novo líder` | tempo de detecção + eleição |
| `AAAA-MM-DD-versao.txt` | `git rev-parse --short HEAD; java -version; docker --version` | mesmo commit nas duas máquinas |

## Números que o relatório precisa (e que vieram daqui)

Preencher `relatorio/secoes/04-resultados.tex` só com o que estiver arquivado nesta pasta:

- tempo total das 30 rodadas e ms por rodada (diferença entre `iniciando rodada 1/30` e
  `rodada 30 concluída`);
- melhor rota final (deve ser **igual** nos dois logs);
- tempo de failover (de `Ctrl+C` até `Novo líder`);
- qualquer anomalia observada (eleição a mais, rodada repetida, timeout).

## Regras práticas

- **Um commit igual nas duas máquinas** — anotar o hash em `AAAA-MM-DD-versao.txt`.
- Não editar log à mão: se precisar recortar, salve o original em `*.log` e o recorte em `*.txt`.
- Se o Wi-Fi bloquear tráfego entre os hosts (ver `../LAN.md` §4), guarde o diagnóstico —
  ele serve de evidência tanto quanto um ensaio que funcionou.
