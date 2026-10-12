package br.pucminas.cd.aco.rpc;

import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Autenticação por token compartilhado do cluster (variável CLUSTER_TOKEN).
 * Toda chamada leva o header "x-cluster-token"; o servidor rejeita quem não o apresenta.
 *
 * <p>Limitação: sem TLS o token trafega em claro na rede local.
 */
public final class ClusterAuth {
    static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of("x-cluster-token", Metadata.ASCII_STRING_MARSHALLER);

    private ClusterAuth() {}

    /** Anexa o token às chamadas de saída. */
    public static ClientInterceptor clientInterceptor(String token) {
        Metadata headers = new Metadata();
        headers.put(TOKEN_KEY, token);
        return MetadataUtils.newAttachHeadersInterceptor(headers);
    }

    /** Rejeita chamadas de entrada sem o token correto. */
    public static ServerInterceptor serverInterceptor(String token) {
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                String received = headers.get(TOKEN_KEY);
                // Comparação em tempo constante para não vazar o token por timing
                if (received == null
                        || !MessageDigest.isEqual(expected, received.getBytes(StandardCharsets.UTF_8))) {
                    call.close(Status.UNAUTHENTICATED.withDescription("token do cluster inválido"), new Metadata());
                    return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
    }
}
