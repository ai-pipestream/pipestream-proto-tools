package ai.pipestream.proto.server;

import ai.pipestream.proto.json.MalformedProtobufJsonException;
import ai.pipestream.proto.json.ProtobufJsonException;
import ai.pipestream.proto.rest.MethodNotFoundException;
import ai.pipestream.proto.rest.ServiceNotFoundException;
import ai.pipestream.proto.rest.UnauthorizedProtoRestException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoRestHttpSupportTest {

    @Test
    void allowsDocumentedHttpMethods() {
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("POST")).isTrue();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("put")).isTrue();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("PATCH")).isTrue();
        // The OpenAPI generator honors @ProtoRestExposed(httpMethods={"GET","DELETE"}).
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("GET")).isTrue();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("delete")).isTrue();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("OPTIONS")).isFalse();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("HEAD")).isFalse();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod(null)).isFalse();
    }

    @Test
    void bodyOrEmptyJsonDefaultsMissingBodies() {
        assertThat(ProtoRestHttpSupport.bodyOrEmptyJson(null)).isEqualTo("{}");
        assertThat(ProtoRestHttpSupport.bodyOrEmptyJson("")).isEqualTo("{}");
        assertThat(ProtoRestHttpSupport.bodyOrEmptyJson("  ")).isEqualTo("{}");
        assertThat(ProtoRestHttpSupport.bodyOrEmptyJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void parsesServiceMethodFromPrefixedPath() {
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/grpc-json/Echo/ping", "/grpc-json"))
                .contains(new String[] {"Echo", "ping"});
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/other/Echo/ping", "/grpc-json")).isEmpty();
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/grpc-json/Echo", "/grpc-json")).isEmpty();
    }

    @Test
    void requiresSegmentBoundaryAfterPrefix() {
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/grpc-jsonFoo/Bar", "/grpc-json")).isEmpty();
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/grpc-json-v2/Echo/ping", "/grpc-json")).isEmpty();
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/grpc-json/Echo/ping", "/grpc-json/"))
                .contains(new String[] {"Echo", "ping"});
    }

    @Test
    void parsesAndDecodesQuery() {
        assertThat(ProtoRestHttpSupport.parseQuery("a=1&b=hello%20world"))
                .containsEntry("a", "1")
                .containsEntry("b", "hello world");
        assertThat(ProtoRestHttpSupport.parseQuery(null)).isEmpty();
        assertThat(ProtoRestHttpSupport.parseQuery("nolue&=novalue")).doesNotContainKey("");
    }

    @Test
    void normalizesHeaders() {
        assertThat(ProtoRestHttpSupport.normalizeHeaders(Map.of("API_Token", "x")))
                .containsEntry("api_token", "x");
        assertThat(ProtoRestHttpSupport.normalizeHeaders(null)).isEmpty();
    }

    @Test
    void mapsExceptionsToStatusCodes() {
        assertThat(ProtoRestHttpSupport.statusFor(new UnauthorizedProtoRestException("nope"))).isEqualTo(401);
        assertThat(ProtoRestHttpSupport.statusFor(new ServiceNotFoundException("s"))).isEqualTo(404);
        assertThat(ProtoRestHttpSupport.statusFor(new MethodNotFoundException("Echo", "m"))).isEqualTo(404);
        assertThat(ProtoRestHttpSupport.statusFor(new MalformedProtobufJsonException("bad", "{}"))).isEqualTo(400);
        assertThat(ProtoRestHttpSupport.statusFor(new RuntimeException("x"))).isEqualTo(500);
    }

    @Test
    void responseSerializationFailureIsServerError() {
        // Plain ProtobufJsonException means the server failed to serialize its own response.
        assertThat(ProtoRestHttpSupport.statusFor(
                new ProtobufJsonException("Failed to serialize protobuf message to JSON")))
                .isEqualTo(500);
        assertThat(ProtoRestHttpSupport.errorJson(
                new ProtobufJsonException("Failed to serialize protobuf message to JSON")))
                .contains("\"status\":500");
    }

    @Test
    void errorJsonEscapesAndIncludesStatus() {
        String json = ProtoRestHttpSupport.errorJson(new ServiceNotFoundException("missing \"svc\""));
        assertThat(json).contains("\"status\":404").contains("missing \\\"svc\\\"");
    }

    @Test
    void unwrapFindsNestedProtoRestException() {
        Throwable wrapped = new RuntimeException(new MethodNotFoundException("Echo", "m"));
        assertThat(ProtoRestHttpSupport.unwrap(wrapped)).isInstanceOf(MethodNotFoundException.class);
    }
}

class ProtoToolsServerConfigTest {

    @Test
    void defaultsAndNormalization() {
        ProtoToolsServerConfig defaults = ProtoToolsServerConfig.defaults();
        assertThat(defaults.host()).isEqualTo("0.0.0.0");
        assertThat(defaults.port()).isEqualTo(8080);
        assertThat(defaults.restPathPrefix()).isEqualTo("/grpc-json");

        assertThat(new ProtoToolsServerConfig(" ", 9, "grpc-json/", null, null).host())
                .isEqualTo("0.0.0.0");
        assertThat(new ProtoToolsServerConfig("h", 9, "grpc-json/", null, null).restPathPrefix())
                .isEqualTo("/grpc-json");
        assertThat(defaults.withPort(9090).port()).isEqualTo(9090);
    }

    @Test
    void rejectsInvalidPort() {
        assertThatThrownBy(() -> new ProtoToolsServerConfig("h", -1, "/x", "/o", "/h"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
