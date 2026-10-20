/**
 * O problema: Otimização por Colônia de Formigas (ACO) aplicada ao TSP simétrico euclidiano.
 *
 * <ul>
 *   <li>{@link br.pucminas.cd.aco.aco.TspInstance}: cidades e matriz de distâncias (aleatória com semente
 *       ou arquivo TSPLIB), mais a heurística do vizinho mais próximo usada como linha de base;</li>
 *   <li>{@link br.pucminas.cd.aco.aco.AntColony}: constrói rotas probabilísticas guiadas por
 *       feromônio (τ) e visibilidade (η = 1/d);</li>
 *   <li>{@link br.pucminas.cd.aco.aco.PheromoneMatrix}: réplica local da matriz de feromônio global,
 *       o recurso compartilhado protegido pela exclusão mútua distribuída;</li>
 *   <li>{@link br.pucminas.cd.aco.aco.Tour} e {@link br.pucminas.cd.aco.aco.BestTracker}: rota e
 *       melhor rota conhecida.</li>
 * </ul>
 *
 * <p>Este pacote não conhece a rede: é usado por {@code round}, que decide quando rodar as formigas
 * e quando escrever na matriz (dentro da seção crítica de {@code mutex}).
 *
 * <p>Ver docs/algoritmos/aco-tsp.md.
 */
package br.pucminas.cd.aco.aco;
