/**
 * Exclusão mútua distribuída pelo algoritmo de Ricart-Agrawala.
 *
 * <ul>
 *   <li>{@link br.pucminas.cd.aco.mutex.RicartAgrawala}: máquina de estados RELEASED / WANTED / HELD,
 *       prioridade pelo par (timestamp de Lamport, id do nó);</li>
 *   <li>{@link br.pucminas.cd.aco.mutex.MutexTransport}: o que o algoritmo precisa da rede,
 *       implementado por {@code rpc.PeerNetwork} e por fakes nos testes.</li>
 * </ul>
 *
 * <p>O recurso protegido é a matriz de feromônio global ({@code aco.PheromoneMatrix}): a seção crítica
 * é "evaporar, depositar e propagar a matriz a todos", executada por {@code round.RoundWorker}.
 *
 * <p>Ver docs/algoritmos/ricart-agrawala.md.
 */
package br.pucminas.cd.aco.mutex;
