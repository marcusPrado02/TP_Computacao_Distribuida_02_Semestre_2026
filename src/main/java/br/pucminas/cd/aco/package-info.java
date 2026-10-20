/**
 * Raiz do nó do ACO distribuído para o Caixeiro-Viajante (TSP).
 *
 * <p>Todos os nós do cluster rodam o mesmo programa. {@link br.pucminas.cd.aco.NodeMain} lê a
 * configuração do ambiente e delega a {@link br.pucminas.cd.aco.Node}, que monta os módulos
 * (composition root) e controla o ciclo de vida: servidor gRPC, eleição, monitor de líder,
 * exclusão mútua e rodadas.
 *
 * <p>Mapa dos pacotes:
 * <ul>
 *   <li>{@code aco}: formigas, matriz de feromônio e instância do TSP (o problema em si);</li>
 *   <li>{@code round}: rodadas síncronas, papéis de líder (coordenador) e trabalhador;</li>
 *   <li>{@code election}: eleição de líder (Bully) e detector de falha do líder;</li>
 *   <li>{@code clock}: relógio lógico de Lamport;</li>
 *   <li>{@code mutex}: exclusão mútua distribuída (Ricart-Agrawala);</li>
 *   <li>{@code rpc}: cliente e servidor gRPC, autenticação do cluster;</li>
 *   <li>{@code config}, {@code util}: configuração por variáveis de ambiente e log.</li>
 * </ul>
 *
 * <p>Ver docs/ARQUITETURA.md para a visão geral e docs/concorrencia.md para o modelo de threads.
 */
package br.pucminas.cd.aco;
