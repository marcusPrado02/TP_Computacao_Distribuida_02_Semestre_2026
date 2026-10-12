package br.pucminas.cd.aco.aco;

/** Rota fechada do TSP: ordem das cidades e comprimento total. */
public record Tour(int[] cities, double length) {}
