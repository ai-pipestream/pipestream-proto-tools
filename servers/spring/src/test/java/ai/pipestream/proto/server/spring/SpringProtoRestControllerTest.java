package ai.pipestream.proto.server.spring;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpringProtoRestControllerTest {

    private MockMvc mockMvc;

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
        SpringProtoRestController controller =
                new SpringProtoRestController(gateway, ProtoToolsServerConfig.defaults());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("pipestream.proto.rest.health-path", "/health")
                .addPlaceholderValue("pipestream.proto.rest.openapi-path", "/openapi.json")
                .addPlaceholderValue("pipestream.proto.rest.path-prefix", "/grpc-json")
                .build();
        assertThat(SpringProtoRestController.ENGINE_ID).isEqualTo("spring");
    }

    @Test
    void healthAndOpenApi() throws Exception {
        String health = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(health).contains("UP");

        String openApi = mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(openApi).contains("EchoService");
    }

    @Test
    void invokesViaGetAndDeleteWithoutBody() throws Exception {
        String viaGet = mockMvc.perform(get("/grpc-json/EchoService/Echo"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(viaGet).contains("hello ");

        String viaDelete = mockMvc.perform(delete("/grpc-json/EchoService/Echo"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(viaDelete).contains("hello ");
    }

    @Test
    void invokeEchoAndAuthFailures() throws Exception {
        String body = mockMvc.perform(post("/grpc-json/EchoService/Echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("hello Ada");

        mockMvc.perform(post("/grpc-json/SecureService/Ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/grpc-json/Missing/Echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
