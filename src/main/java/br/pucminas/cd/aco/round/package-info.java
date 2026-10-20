/**
 * Rodadas do ACO distribuído: quem dispara, quem trabalha e como uma rodada termina.
 *
 * <ul>
 *   <li>{@link br.pucminas.cd.aco.round.RoundCoordinator}: papel de líder. Envia {@code StartRound},
 *       espera {@code RoundDone} dos nós vivos e acompanha a melhor rota global;</li>
 *   <li>{@link br.pucminas.cd.aco.round.RoundWorker}: papel de todo nó. Roda as formigas locais, entra
 *       na seção crítica para atualizar e propagar o feromônio e avisa o líder;</li>
 *   <li>{@link br.pucminas.cd.aco.round.RoundSink}: destino do aviso de fim de rodada, que permite ao
 *       trabalhador do próprio líder avisar sem passar pela rede.</li>
 * </ul>
 *
 * <p>Conversa com {@code aco} (cálculo), {@code mutex} (seção crítica), {@code election} (quem é o líder)
 * e {@code rpc} (mensagens).
 *
 * <p>Ver docs/rodadas.md.
 */
package br.pucminas.cd.aco.round;
