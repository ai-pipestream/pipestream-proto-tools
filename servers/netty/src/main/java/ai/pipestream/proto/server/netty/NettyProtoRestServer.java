package ai.pipestream.proto.server.netty;

import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.server.ProtoRestServerHost;
import ai.pipestream.proto.server.ProtoToolsServerConfig;

/**
 * Netty HTTP host scaffold for protobuf JSON/REST.
 *
 * <p>Implements the same {@link ProtoRestServerHost} contract as JDK/Vert.x.
 * Full pipeline (HttpServerCodec → aggregator → gateway) lands next; until then
 * prefer {@code servers/jdk} or {@code servers/vertx}.
 */
public final class NettyProtoRestServer implements ProtoRestServerHost {

    public static final String ENGINE_ID = "netty";

    private final ProtoToolsServerConfig config;
    private final ProtoRestGateway gateway;

    public NettyProtoRestServer(ProtoToolsServerConfig config, ProtoRestGateway gateway) {
        this.config = config;
        this.gateway = gateway;
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public int start() {
        throw new UnsupportedOperationException(
                "Netty host scaffold — wire HttpServerCodec + ProtoRestGateway next. Use servers/jdk or servers/vertx for now.");
    }

    @Override
    public int actualPort() {
        throw new IllegalStateException("Not started");
    }

    @Override
    public ProtoToolsServerConfig config() {
        return config;
    }

    @Override
    public ProtoRestGateway gateway() {
        return gateway;
    }

    @Override
    public void close() {
        // no-op until start() is implemented
    }
}
