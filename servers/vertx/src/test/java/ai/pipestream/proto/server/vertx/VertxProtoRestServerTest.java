package ai.pipestream.proto.server.vertx;

import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.rest.ApiTokenRequirement;
import ai.pipestream.proto.rest.ProtoApiTokenValidator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethod;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class VertxProtoRestServerTest {

    private VertxProtoRestServer server;
    private int port;

    @BeforeEach
    void setUp() {
        ProtoRestMethodRegistry registry = new ProtoRestMethodRegistry();
        registry.register(ProtoRestMethod.builder("EchoService", "Echo", request -> {
                    Struct in = (Struct) request;
                    String name = in.getFieldsOrDefault("name", Value.getDefaultInstance()).getStringValue();
                    return Struct.newBuilder()
                            .putFields("message", Value.newBuilder().setStringValue("hello " + name).build())
                            .build();
                })
                .requestType(Struct.class)
                .apiToken(ApiTokenRequirement.apiKeyHeader("api_token"))
                .summary("Echo")
                .build());

        server = new VertxProtoRestServer(
                ProtoToolsServerConfig.defaults().withPort(0).withHost("127.0.0.1"),
                new ProtoRestGateway(
                        registry,
                        new ProtobufJsonTranscoder(),
                        ProtoApiTokenValidator.sharedSecret("secret")));
        port = server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void invokeViaVertx(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.post(port, "127.0.0.1", "/grpc-json/EchoService/Echo")
                .putHeader("content-type", "application/json")
                .putHeader("api_token", "secret")
                .sendBuffer(Buffer.buffer("{\"name\":\"vertx\"}"))
                .onComplete(context.succeeding(response -> context.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.bodyAsString()).contains("hello vertx");
                    assertThat(server.engineId()).isEqualTo("vertx");
                    context.completeNow();
                })));
    }
}
