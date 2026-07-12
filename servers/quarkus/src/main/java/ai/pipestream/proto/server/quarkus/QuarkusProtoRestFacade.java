package ai.pipestream.proto.server.quarkus;

import ai.pipestream.proto.openapi.ProtoOpenApiGenerator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.server.ProtoRestHttpSupport;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Quarkus (Vert.x 4 era) adapter — JAX-RS / RESTEasy resources call this bean.
 *
 * <p>Quarkus is not yet on Vert.x 5; until it is, roll REST here rather than using
 * {@code servers/vertx}. When Quarkus moves to Vert.x 5, prefer mounting
 * {@code VertxProtoRestServer#createRouter()}.
 */
@ApplicationScoped
public class QuarkusProtoRestFacade {

    public static final String ENGINE_ID = "quarkus";

    private final ProtoRestGateway gateway;
    private final ProtoToolsServerConfig config;
    private final ProtoOpenApiGenerator openApiGenerator;

    public QuarkusProtoRestFacade(ProtoRestGateway gateway) {
        this(gateway, ProtoToolsServerConfig.defaults());
    }

    @Inject
    public QuarkusProtoRestFacade(ProtoRestGateway gateway, ProtoToolsServerConfig config) {
        this.gateway = gateway;
        this.config = config;
        this.openApiGenerator = new ProtoOpenApiGenerator(
                "Protobuf REST Gateway", "1.0.0", "/", config.restPathPrefix());
    }

    public String engineId() {
        return ENGINE_ID;
    }

    public String healthJson() {
        return "{\"status\":\"UP\"}";
    }

    public String openApiJson() {
        return openApiGenerator.generateJson(gateway.getRegistry());
    }

    public Result invoke(String service, String method, String body, Map<String, String> headers, Map<String, String> query) {
        try {
            Map<String, String> normalized = headers == null ? Map.of() : headers.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> e.getKey().toLowerCase(Locale.ROOT),
                            Map.Entry::getValue,
                            (a, b) -> b));
            String json = gateway.invoke(
                    service,
                    method,
                    ProtoRestHttpSupport.bodyOrEmptyJson(body),
                    normalized,
                    query == null ? Map.of() : query);
            return new Result(200, json);
        } catch (Throwable err) {
            return new Result(ProtoRestHttpSupport.statusFor(err), ProtoRestHttpSupport.errorJson(err));
        }
    }

    public ProtoToolsServerConfig config() {
        return config;
    }

    public record Result(int status, String body) {
    }
}
