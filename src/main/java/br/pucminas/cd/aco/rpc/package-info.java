/**
 * Comunicação entre nós via gRPC (contrato em src/main/proto/node.proto).
 *
 * <ul>
 *   <li>{@link br.pucminas.cd.aco.rpc.PeerNetwork}: lado cliente. Um canal por peer, deadline em toda
 *       chamada, carimbo de Lamport em toda mensagem e visão de quais peers estão vivos;</li>
 *   <li>{@link br.pucminas.cd.aco.rpc.NodeServiceImpl}: lado servidor. Valida o remetente, aplica a
 *       regra de recebimento de Lamport e delega ao módulo responsável;</li>
 *   <li>{@link br.pucminas.cd.aco.rpc.ClusterAuth}: autenticação por token compartilhado do cluster;</li>
 *   <li>{@code Protos}: conversões entre tipos do domínio e campos repeated do protobuf.</li>
 * </ul>
 *
 * <p>É o único pacote que conhece o gRPC: {@code election} e {@code mutex} dependem só das interfaces
 * {@code ElectionTransport} e {@code MutexTransport}, que {@code PeerNetwork} implementa.
 *
 * <p>Ver docs/rede-grpc.md.
 */
package br.pucminas.cd.aco.rpc;
