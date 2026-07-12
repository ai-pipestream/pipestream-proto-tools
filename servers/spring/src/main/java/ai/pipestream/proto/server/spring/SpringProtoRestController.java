package ai.pipestream.proto.server.spring;

import ai.pipestream.proto.openapi.ProtoOpenApiGenerator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.server.ProtoRestHttpSupport;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring MVC controller that mounts {@link ProtoRestGateway}.
 * Register as a bean alongside a configured gateway (see {@code …-spring} auto-config).
 */
@RestController
public class SpringProtoRestController {

    public static final String ENGINE_ID = "spring";

    private final ProtoRestGateway gateway;
    private final ProtoToolsServerConfig config;
    private final ProtoOpenApiGenerator openApiGenerator;

    public SpringProtoRestController(ProtoRestGateway gateway, ProtoToolsServerConfig config) {
        this.gateway = gateway;
        this.config = config;
        this.openApiGenerator = new ProtoOpenApiGenerator(
                "Protobuf REST Gateway", "1.0.0", "/", config.restPathPrefix());
    }

    @GetMapping("${pipestream.proto.rest.health-path:/health}")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping(
            value = "${pipestream.proto.rest.openapi-path:/openapi.json}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public String openApi() {
        return openApiGenerator.generateJson(gateway.getRegistry());
    }

    @RequestMapping(
            path = "${pipestream.proto.rest.path-prefix:/grpc-json}/{serviceName}/{methodName}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH,
                    RequestMethod.DELETE},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> invoke(
            @PathVariable("serviceName") String serviceName,
            @PathVariable("methodName") String methodName,
            @RequestBody(required = false) String body,
            @RequestHeader Map<String, String> headers,
            @RequestParam Map<String, String> query) {
        try {
            Map<String, String> normalized = headers.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> e.getKey().toLowerCase(Locale.ROOT),
                            Map.Entry::getValue,
                            (a, b) -> b));
            String json = gateway.invoke(
                    serviceName, methodName, ProtoRestHttpSupport.bodyOrEmptyJson(body), normalized, query);
            return ResponseEntity.ok(json);
        } catch (Throwable err) {
            return ResponseEntity.status(ProtoRestHttpSupport.statusFor(err))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ProtoRestHttpSupport.errorJson(err));
        }
    }
}
