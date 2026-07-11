package ai.pipestream.proto.server;

import java.util.Objects;

/**
 * Shared configuration for all protobuf JSON/REST server hosts
 * (JDK, Vert.x, Netty, Quarkus, Spring, Micronaut, …).
 */
public record ProtoToolsServerConfig(
        String host,
        int port,
        String restPathPrefix,
        String openApiPath,
        String healthPath) {

    public ProtoToolsServerConfig {
        host = host == null || host.isBlank() ? "0.0.0.0" : host;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        restPathPrefix = normalizePrefix(restPathPrefix == null ? "/grpc-json" : restPathPrefix);
        openApiPath = normalizeAbsolute(openApiPath == null ? "/openapi.json" : openApiPath);
        healthPath = normalizeAbsolute(healthPath == null ? "/health" : healthPath);
    }

    public static ProtoToolsServerConfig defaults() {
        return new ProtoToolsServerConfig("0.0.0.0", 8080, "/grpc-json", "/openapi.json", "/health");
    }

    public ProtoToolsServerConfig withPort(int port) {
        return new ProtoToolsServerConfig(host, port, restPathPrefix, openApiPath, healthPath);
    }

    public ProtoToolsServerConfig withHost(String host) {
        return new ProtoToolsServerConfig(host, port, restPathPrefix, openApiPath, healthPath);
    }

    public ProtoToolsServerConfig withRestPathPrefix(String restPathPrefix) {
        return new ProtoToolsServerConfig(host, port, restPathPrefix, openApiPath, healthPath);
    }

    private static String normalizePrefix(String path) {
        Objects.requireNonNull(path, "path");
        String p = path.startsWith("/") ? path : "/" + path;
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String normalizeAbsolute(String path) {
        return normalizePrefix(path);
    }
}
