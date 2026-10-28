package br.pucminas.cd.aco.aco;

/**
 * Rota fechada do TSP: ordem das cidades e comprimento total (inclui a volta à cidade inicial).
 *
 * <p>O array não é copiado; por convenção ninguém o altera depois de criar a rota.
 *
 * @param cities permutação dos índices 0..n-1
 * @param length soma das distâncias da rota fechada
 */
public record Tour(int[] cities, double length) {}
