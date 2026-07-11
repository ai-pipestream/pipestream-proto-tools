package ai.pipestream.proto.server;

import ai.pipestream.proto.rest.ProtoRestGateway;

/**
 * Common host contract for every {@code servers/*} adapter.
 * Logic lives in {@link ProtoRestGateway}; hosts only bind HTTP.
 */
public interface ProtoRestServerHost extends AutoCloseable {

    /** Starts listening; returns the bound port (useful when {@code port=0}). */
    int start();

    int actualPort();

    ProtoToolsServerConfig config();

    ProtoRestGateway gateway();

    String engineId();

    @Override
    void close();
}
