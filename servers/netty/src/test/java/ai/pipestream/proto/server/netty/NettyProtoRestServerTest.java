package ai.pipestream.proto.server.netty;

import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.rest.ApiTokenRequirement;
import ai.pipestream.proto.rest.ProtoApiTokenValidator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethod;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NettyProtoRestServerTest {

    private NettyProtoRestServer server;
    private int port;
    private HttpClient client;

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

        server = new NettyProtoRestServer(
                ProtoToolsServerConfig.defaults().withPort(0).withHost("127.0.0.1"),
                new ProtoRestGateway(
                        registry,
                        new ProtobufJsonTranscoder(),
                        ProtoApiTokenValidator.sharedSecret("secret")));
        port = server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void engineIdIsNetty() {
        assertThat(server.engineId()).isEqualTo("netty");
        assertThat(server.actualPort()).isEqualTo(port).isGreaterThan(0);
    }

    @Test
    void healthOpenApiAndInvoke() throws Exception {
        assertThat(get("/health").statusCode()).isEqualTo(200);
        assertThat(get("/health").body()).contains("UP");
        assertThat(get("/openapi.json").body()).contains("EchoService");

        HttpResponse<String> ok = client.send(
                HttpRequest.newBuilder(uri("/grpc-json/EchoService/Echo"))
                        .header("content-type", "application/json")
                        .header("api_token", "secret")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"netty\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("hello netty");
    }

    @Test
    void getAndDeleteInvokeWithEmptyJsonBody() throws Exception {
        HttpResponse<String> viaGet = client.send(
                HttpRequest.newBuilder(uri("/grpc-json/EchoService/Echo"))
                        .header("api_token", "secret")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(viaGet.statusCode()).isEqualTo(200);
        assertThat(viaGet.body()).contains("hello ");

        HttpResponse<String> viaDelete = client.send(
                HttpRequest.newBuilder(uri("/grpc-json/EchoService/Echo"))
                        .header("api_token", "secret")
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(viaDelete.statusCode()).isEqualTo(200);
        assertThat(viaDelete.body()).contains("hello ");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri("/grpc-json/EchoService/Echo"))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"status\":401");
    }

    @Test
    void unknownRouteIs404() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri("/grpc-json/EchoService"))
                        .header("api_token", "secret")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(404);
    }

    @Test
    void undocumentedMethodStill405s() throws Exception {
        HttpResponse<String> options = client.send(
                HttpRequest.newBuilder(uri("/grpc-json/EchoService/Echo"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(options.statusCode()).isEqualTo(405);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
