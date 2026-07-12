package ai.pipestream.proto.openapi;

import ai.pipestream.proto.rest.ApiTokenRequirement;
import ai.pipestream.proto.rest.ProtoApiToken;
import ai.pipestream.proto.rest.ProtoRestMethod;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt64Value;
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

    @Test
    void registersDistinctSecuritySchemePerTokenConfig() {
        ProtoRestMethodRegistry registry = new ProtoRestMethodRegistry();
        registry.register(ProtoRestMethod.builder("BearerService", "Ping", req -> req)
                .requestType(Struct.class)
                .apiToken(ApiTokenRequirement.bearer())
                .build());
        registry.register(ProtoRestMethod.builder("QueryKeyService", "Ping", req -> req)
                .requestType(Struct.class)
                .apiToken(new ApiTokenRequirement(
                        "api_token",
                        ProtoApiToken.In.QUERY,
                        ProtoApiToken.Scheme.API_KEY,
                        null,
                        true,
                        "API access token"))
                .build());
        // Same config as BearerService — must reuse its scheme, not mint a third.
        registry.register(ProtoRestMethod.builder("BearerTwinService", "Ping", req -> req)
                .requestType(Struct.class)
                .apiToken(ApiTokenRequirement.bearer())
                .build());

        Map<String, Object> doc = new ProtoOpenApiGenerator().generate(registry);

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(schemes.keySet()).containsExactlyInAnyOrder("ApiToken", "ApiToken_2");

        String bearerRef = securityRefOf(doc, "/grpc-json/BearerService/Ping");
        String queryRef = securityRefOf(doc, "/grpc-json/QueryKeyService/Ping");
        assertThat(bearerRef).isNotEqualTo(queryRef);
        assertThat((Map<String, Object>) schemes.get(bearerRef))
                .containsEntry("type", "http")
                .containsEntry("scheme", "bearer");
        assertThat((Map<String, Object>) schemes.get(queryRef))
                .containsEntry("type", "apiKey")
                .containsEntry("name", "api_token")
                .containsEntry("in", "query");
        // Identical configs share one scheme instead of minting a third.
        assertThat(securityRefOf(doc, "/grpc-json/BearerTwinService/Ping")).isEqualTo(bearerRef);
    }

    @Test
    void emits64BitIntegersAsJsonStrings() {
        ProtoRestMethodRegistry registry = new ProtoRestMethodRegistry();
        registry.register(ProtoRestMethod.builder("Clock", "now", req -> req)
                .requestType(Timestamp.class)
                .build());
        registry.register(ProtoRestMethod.builder("Counter", "get", req -> req)
                .requestType(UInt64Value.class)
                .build());

        Map<String, Object> schemas = schemasOf(new ProtoOpenApiGenerator().generate(registry));

        assertThat(propertyOf(schemas, "google_protobuf_Timestamp", "seconds"))
                .containsEntry("type", "string")
                .containsEntry("format", "int64");
        assertThat(propertyOf(schemas, "google_protobuf_Timestamp", "nanos"))
                .containsEntry("type", "integer")
                .containsEntry("format", "int32");
        assertThat(propertyOf(schemas, "google_protobuf_UInt64Value", "value"))
                .containsEntry("type", "string")
                .containsEntry("format", "uint64");
    }

    @Test
    void mapFieldsDoNotEmitSyntheticEntrySchemas() {
        ProtoRestMethodRegistry registry = new ProtoRestMethodRegistry();
        registry.register(ProtoRestMethod.builder("StructSvc", "echo", req -> req)
                .requestType(Struct.class)
                .build());

        Map<String, Object> schemas = schemasOf(new ProtoOpenApiGenerator().generate(registry));

        assertThat(schemas.keySet()).noneMatch(key -> key.endsWith("Entry"));
        Map<String, Object> fields = propertyOf(schemas, "google_protobuf_Struct", "fields");
        assertThat(fields).containsEntry("type", "object").containsKey("additionalProperties");
    }

    @SuppressWarnings("unchecked")
    private static String securityRefOf(Map<String, Object> doc, String path) {
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as(path).isNotNull();
        Map<String, Object> operation = (Map<String, Object>) pathItem.get("post");
        java.util.List<Map<String, Object>> security =
                (java.util.List<Map<String, Object>>) operation.get("security");
        assertThat(security).as(path + " security").hasSize(1);
        return security.getFirst().keySet().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemasOf(Map<String, Object> doc) {
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        return (Map<String, Object>) components.get("schemas");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyOf(
            Map<String, Object> schemas, String schemaKey, String property) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaKey);
        assertThat(schema).as(schemaKey).isNotNull();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        return (Map<String, Object>) properties.get(property);
    }
}
