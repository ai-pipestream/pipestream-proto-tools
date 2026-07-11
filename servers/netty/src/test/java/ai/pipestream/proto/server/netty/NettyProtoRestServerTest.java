package ai.pipestream.proto.server.netty;

import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.rest.ProtoApiTokenValidator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyProtoRestServerTest {

    @Test
    void scaffoldContract() {
        ProtoRestGateway gateway = new ProtoRestGateway(
                new ProtoRestMethodRegistry(),
                new ProtobufJsonTranscoder(),
                ProtoApiTokenValidator.acceptNonBlank());
        ProtoToolsServerConfig config = ProtoToolsServerConfig.defaults().withPort(0);
        NettyProtoRestServer server = new NettyProtoRestServer(config, gateway);

        assertThat(server.engineId()).isEqualTo("netty");
        assertThat(server.config()).isSameAs(config);
        assertThat(server.gateway()).isSameAs(gateway);
        assertThatThrownBy(server::start)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("scaffold");
        assertThatThrownBy(server::actualPort)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not started");
        server.close();
    }
}
