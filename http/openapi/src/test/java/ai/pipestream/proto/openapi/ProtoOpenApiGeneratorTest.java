package ai.pipestream.proto.openapi;

import ai.pipestream.proto.rest.ApiTokenRequirement;
import ai.pipestream.proto.rest.ProtoRestMethod;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import com.google.protobuf.Struct;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoOpenApiGeneratorTest {

    @Test
    void generatesOpenApiWithSecurityAndSchemas() {
        ProtoRestMethodRegistry registry = new ProtoRestMethodRegistry();
        registry.register(ProtoRestMethod.builder("EchoService", "Echo", req -> req)
                .requestType(Struct.class)
                .apiToken(ApiTokenRequirement.apiKeyHeader("api_token"))
                .summary("Echo")
                .build());

        ProtoOpenApiGenerator generator = new ProtoOpenApiGenerator(
                "Test API", "0.1.0", "http://localhost:8080", "/grpc-json");
        Map<String, Object> doc = generator.generate(registry);

        assertThat(doc.get("openapi")).isEqualTo("3.0.3");
        assertThat(doc).containsKey("paths");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertThat(paths).containsKey("/grpc-json/EchoService/Echo");

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        assertThat(components).containsKeys("schemas", "securitySchemes");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).containsKey("google_protobuf_Struct");

        String json = generator.generateJson(registry);
        assertThat(json).contains("\"openapi\" : \"3.0.3\"").contains("ApiToken");
    }
}
