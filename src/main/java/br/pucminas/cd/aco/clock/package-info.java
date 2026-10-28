/**
 * Relógio lógico de Lamport ({@link br.pucminas.cd.aco.clock.LamportClock}).
 *
 * <p>Um único relógio por nó, compartilhado por todos os módulos. A camada {@code rpc} aplica as regras:
 * todo envio carimba o cabeçalho com {@code tick()} e todo recebimento (requisição ou resposta) chama
 * {@code onReceive(remoto)}. O {@code mutex} usa o valor do relógio como timestamp do pedido de seção
 * crítica e o {@code util.Log} o imprime em cada linha ({@code [L=...]}).
 *
 * <p>Ver docs/algoritmos/lamport.md.
 */
package br.pucminas.cd.aco.clock;
