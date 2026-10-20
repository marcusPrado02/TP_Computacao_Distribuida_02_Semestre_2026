/**
 * Eleição de líder pelo algoritmo Bully e detecção de falha do líder.
 *
 * <ul>
 *   <li>{@link br.pucminas.cd.aco.election.BullyElection}: o nó vivo de maior id vira coordenador;</li>
 *   <li>{@link br.pucminas.cd.aco.election.LeaderMonitor}: heartbeat periódico no líder; após falhas
 *       seguidas, dispara nova eleição;</li>
 *   <li>{@link br.pucminas.cd.aco.election.ElectionTransport}: o que o algoritmo precisa da rede,
 *       implementado por {@code rpc.PeerNetwork} e por fakes nos testes.</li>
 * </ul>
 *
 * <p>Quem usa o resultado: {@code Node} liga o papel de coordenador de {@code round} quando este nó
 * vence, e {@code round.RoundWorker} consulta o líder atual para enviar {@code RoundDone}.
 *
 * <p>Ver docs/algoritmos/bully.md.
 */
package br.pucminas.cd.aco.election;
