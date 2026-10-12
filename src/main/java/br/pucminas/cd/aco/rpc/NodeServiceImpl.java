package br.pucminas.cd.aco.rpc;

import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.election.BullyElection;
import br.pucminas.cd.aco.grpc.Ack;
import br.pucminas.cd.aco.grpc.CoordinatorMsg;
import br.pucminas.cd.aco.grpc.CsReply;
import br.pucminas.cd.aco.grpc.CsRequest;
import br.pucminas.cd.aco.grpc.ElectionMsg;
import br.pucminas.cd.aco.grpc.ElectionReply;
import br.pucminas.cd.aco.grpc.Header;
import br.pucminas.cd.aco.grpc.HeartbeatMsg;
import br.pucminas.cd.aco.grpc.NodeServiceGrpc;
import br.pucminas.cd.aco.grpc.PheromoneUpdate;
import br.pucminas.cd.aco.grpc.RoundResult;
import br.pucminas.cd.aco.grpc.RoundStart;
import br.pucminas.cd.aco.mutex.RicartAgrawala;
import br.pucminas.cd.aco.round.RoundSink;
import br.pucminas.cd.aco.round.RoundWorker;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Lado servidor do gRPC. Toda mensagem recebida atualiza o relógio de Lamport
 * e é delegada ao módulo responsável. Nenhum handler bloqueia.
 */
public final class NodeServiceImpl extends NodeServiceGrpc.NodeServiceImplBase {
    private final LamportClock clock;
    private final PeerNetwork network;
    private final BullyElection election;
    private final RicartAgrawala mutex;
    private final RoundWorker worker;
    private final RoundSink coordinator;

    public NodeServiceImpl(LamportClock clock, PeerNetwork network, BullyElection election,
                           RicartAgrawala mutex, RoundWorker worker, RoundSink coordinator) {
        this.clock = clock;
        this.network = network;
        this.election = election;
        this.mutex = mutex;
        this.worker = worker;
        this.coordinator = coordinator;
    }

    @Override
    public void election(ElectionMsg req, StreamObserver<ElectionReply> obs) {
        int from = receive(req.getHeader());
        boolean ok = election.onElection(from);
        obs.onNext(ElectionReply.newBuilder().setHeader(network.header()).setOk(ok).build());
        obs.onCompleted();
    }

    @Override
    public void coordinator(CoordinatorMsg req, StreamObserver<Ack> obs) {
        receive(req.getHeader());
        election.onCoordinator(req.getLeaderId());
        ack(obs);
    }

    @Override
    public void heartbeat(HeartbeatMsg req, StreamObserver<Ack> obs) {
        receive(req.getHeader());
        ack(obs);
    }

    @Override
    public void requestCS(CsRequest req, StreamObserver<Ack> obs) {
        int from = receive(req.getHeader());
        mutex.onRequest(req.getRequestTs(), from);
        ack(obs);
    }

    @Override
    public void replyCS(CsReply req, StreamObserver<Ack> obs) {
        int from = receive(req.getHeader());
        mutex.onReply(from);
        ack(obs);
    }

    @Override
    public void startRound(RoundStart req, StreamObserver<Ack> obs) {
        receive(req.getHeader());
        worker.onStartRound(req.getRound());
        ack(obs);
    }

    @Override
    public void roundDone(RoundResult req, StreamObserver<Ack> obs) {
        int from = receive(req.getHeader());
        coordinator.onRoundDone(req.getRound(), from, Protos.tour(req.getTourList(), req.getLength()));
        ack(obs);
    }

    @Override
    public void updatePheromone(PheromoneUpdate req, StreamObserver<Ack> obs) {
        receive(req.getHeader());
        worker.onPheromoneUpdate(req.getRound(), Protos.unboxDoubles(req.getTauList()),
                Protos.tour(req.getBestTourList(), req.getBestLength()));
        ack(obs);
    }

    /** Valida o remetente e aplica a regra de recebimento do Lamport. @return id do remetente */
    private int receive(Header header) {
        if (!network.allPeers().contains(header.getSenderId())) {
            throw Status.PERMISSION_DENIED
                    .withDescription("remetente desconhecido: N" + header.getSenderId())
                    .asRuntimeException();
        }
        clock.onReceive(header.getLamport());
        return header.getSenderId();
    }

    private void ack(StreamObserver<Ack> obs) {
        obs.onNext(Ack.newBuilder().setHeader(network.header()).build());
        obs.onCompleted();
    }
}
