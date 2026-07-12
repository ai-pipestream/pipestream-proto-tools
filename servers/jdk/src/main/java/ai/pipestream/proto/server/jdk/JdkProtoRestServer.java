package ai.pipestream.proto.server.jdk;

import ai.pipestream.proto.openapi.ProtoOpenApiGenerator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.server.ProtoRestHttpSupport;
import ai.pipestream.proto.server.ProtoRestServerHost;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zero-extra-dep host: JDK {@link HttpServer} + virtual threads.
 */
public final class JdkProtoRestServer implements ProtoRestServerHost {

    public static final String ENGINE_ID = "jdk";

    private static final Logger LOG = LoggerFactory.getLogger(JdkProtoRestServer.class);

    private final ProtoToolsServerConfig config;
    private final ProtoRestGateway gateway;
    private final ProtoOpenApiGenerator openApiGenerator;
    private final AtomicReference<HttpServer> httpServer = new AtomicReference<>();
    private volatile String cachedOpenApiJson;

    public JdkProtoRestServer(ProtoRestGateway gateway) {
        this(ProtoToolsServerConfig.defaults(), gateway, new ProtoOpenApiGenerator());
    }

    public JdkProtoRestServer(ProtoToolsServerConfig config, ProtoRestGateway gateway) {
        this(config, gateway, new ProtoOpenApiGenerator(
                "Protobuf REST Gateway",
                "1.0.0",
                "/",
                config.restPathPrefix()));
    }

    public JdkProtoRestServer(
            ProtoToolsServerConfig config,
            ProtoRestGateway gateway,
            ProtoOpenApiGenerator openApiGenerator) {
        this.config = Objects.requireNonNull(config, "config");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.openApiGenerator = Objects.requireNonNull(openApiGenerator, "openApiGenerator");
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public int start() {
        if (httpServer.get() != null) {
            throw new IllegalStateException("Server already started");
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            server.createContext(config.healthPath(), this::handleHealth);
            server.createContext(config.openApiPath(), this::handleOpenApi);
            server.createContext(config.restPathPrefix(), this::handleRest);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            httpServer.set(server);
            int bound = server.getAddress().getPort();
            LOG.info("Proto REST JDK host on {}:{} ({} , {})",
                    config.host(), bound, config.restPathPrefix(), config.openApiPath());
            return bound;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start JDK HttpServer", e);
        }
    }

    @Override
    public int actualPort() {
        HttpServer server = httpServer.get();
        if (server == null) {
            throw new IllegalStateException("Server not started");
        }
        return server.getAddress().getPort();
    }

    @Override
    public ProtoToolsServerConfig config() {
        return config;
    }

    @Override
    public ProtoRestGateway gateway() {
        return gateway;
    }

    public void invalidateOpenApiCache() {
        cachedOpenApiJson = null;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        write(exchange, 200, "{\"status\":\"UP\"}");
    }

    private void handleOpenApi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        write(exchange, 200, openApiJson());
    }

    private void handleRest(HttpExchange exchange) throws IOException {
        if (!ProtoRestHttpSupport.isAllowedHttpMethod(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        var route = ProtoRestHttpSupport.parseServiceMethod(
                exchange.getRequestURI().getPath(), config.restPathPrefix());
        if (route.isEmpty()) {
            write(exchange, 404, "{\"error\":\"Expected " + config.restPathPrefix() + "/{service}/{method}\"}");
            return;
        }
        String[] parts = route.get();
        String body = ProtoRestHttpSupport.bodyOrEmptyJson(readBody(exchange));
        Map<String, String> headers = flattenHeaders(exchange.getRequestHeaders());
        Map<String, String> query = ProtoRestHttpSupport.parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            write(exchange, 200, gateway.invoke(parts[0], parts[1], body, headers, query));
        } catch (Throwable err) {
            write(exchange, ProtoRestHttpSupport.statusFor(err), ProtoRestHttpSupport.errorJson(err));
        }
    }

    private String openApiJson() {
        String cached = cachedOpenApiJson;
        if (cached == null) {
            cached = openApiGenerator.generateJson(gateway.getRegistry());
            cachedOpenApiJson = cached;
        }
        return cached;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> flattenHeaders(Headers headers) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            out.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getFirst());
        }
        return out;
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        HttpServer server = httpServer.getAndSet(null);
        if (server != null) {
            server.stop(0);
        }
    }
}
