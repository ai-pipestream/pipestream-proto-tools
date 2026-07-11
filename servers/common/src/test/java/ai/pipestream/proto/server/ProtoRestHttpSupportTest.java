package ai.pipestream.proto.server;

import ai.pipestream.proto.json.MalformedProtobufJsonException;
import ai.pipestream.proto.rest.MethodNotFoundException;
import ai.pipestream.proto.rest.ServiceNotFoundException;
import ai.pipestream.proto.rest.UnauthorizedProtoRestException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoRestHttpSupportTest {

    @Test
    void allowsMutatingHttpMethodsOnly() {
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("POST")).isTrue();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("put")).isTrue();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("PATCH")).isTrue();
        assertThat(ProtoRestHttpSupport.isAllowedHttpMethod("GET")).isFalse();
    }

    @Test
    void parsesServiceMethodFromPrefixedPath() {
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/grpc-json/Echo/ping", "/grpc-json"))
                .contains(new String[] {"Echo", "ping"});
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/other/Echo/ping", "/grpc-json")).isEmpty();
        assertThat(ProtoRestHttpSupport.parseServiceMethod("/grpc-json/Echo", "/grpc-json")).isEmpty();
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
