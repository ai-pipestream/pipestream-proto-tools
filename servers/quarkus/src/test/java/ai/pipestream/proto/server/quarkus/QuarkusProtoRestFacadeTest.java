package ai.pipestream.proto.server.quarkus;

import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.rest.ProtoApiTokenValidator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethod;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuarkusProtoRestFacadeTest {

    private QuarkusProtoRestFacade facade;

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
                .build());
        ProtoRestGateway gateway = new ProtoRestGateway(
                registry,
                new ProtobufJsonTranscoder(),
                ProtoApiTokenValidator.acceptNonBlank());
        facade = new QuarkusProtoRestFacade(gateway, ProtoToolsServerConfig.defaults());
    }

    @Test
    void invokesWithNullBodyCoerced() {
        assertThat(facade.engineId()).isEqualTo("quarkus");
        assertThat(facade.healthJson()).contains("UP");
        assertThat(facade.openApiJson()).contains("EchoService");

        QuarkusProtoRestFacade.Result ok = facade.invoke(
                "EchoService", "Echo", "{\"name\":\"Ada\"}", Map.of(), Map.of());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).contains("hello Ada");

        QuarkusProtoRestFacade.Result missing = facade.invoke("Nope", "Echo", null, null, null);
        assertThat(missing.status()).isEqualTo(404);
    }
}
