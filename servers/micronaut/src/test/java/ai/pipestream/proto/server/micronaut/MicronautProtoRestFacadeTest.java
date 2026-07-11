package ai.pipestream.proto.server.micronaut;

import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.rest.ApiTokenRequirement;
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

class MicronautProtoRestFacadeTest {

    private MicronautProtoRestFacade facade;

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
        registry.register(ProtoRestMethod.builder("SecureService", "Ping", request ->
                        Struct.newBuilder()
                                .putFields("ok", Value.newBuilder().setBoolValue(true).build())
                                .build())
                .requestType(Struct.class)
                .apiToken(ApiTokenRequirement.apiKeyHeader("api_token"))
                .build());
        ProtoRestGateway gateway = new ProtoRestGateway(
                registry,
                new ProtobufJsonTranscoder(),
                ProtoApiTokenValidator.sharedSecret("secret-token"));
        facade = new MicronautProtoRestFacade(gateway, ProtoToolsServerConfig.defaults());
    }

    @Test
    void invokesAndReportsEngine() {
        assertThat(facade.engineId()).isEqualTo("micronaut");
        assertThat(facade.healthJson()).contains("UP");
        assertThat(facade.openApiJson()).contains("EchoService");

        MicronautProtoRestFacade.Result ok = facade.invoke(
                "EchoService", "Echo", "{\"name\":\"world\"}", Map.of(), Map.of());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).contains("hello world");
    }

    @Test
    void mapsMissingServiceAndUnauthorized() {
        assertThat(facade.invoke("Missing", "Echo", "{}", null, null).status()).isEqualTo(404);
        assertThat(facade.invoke("SecureService", "Ping", "{}", Map.of(), Map.of()).status()).isEqualTo(401);
        assertThat(facade.invoke(
                "SecureService", "Ping", "{}", Map.of("api_token", "secret-token"), Map.of()).status())
                .isEqualTo(200);
    }
}
