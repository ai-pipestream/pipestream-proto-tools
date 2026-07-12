package ai.pipestream.proto.rest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pluggable API-token check used by {@link ProtoRestGateway}.
 * Framework glue (Quarkus/Spring) or a simple shared-secret validator can implement this.
 */
@FunctionalInterface
public interface ProtoApiTokenValidator {

    /**
     * @param tokenConfig scheme describing how the token is expected
     * @param headers lowercase header name → value (may be empty)
     * @param queryParams query name → value (may be empty)
     * @return empty if valid; otherwise a reason string
     */
    Optional<String> validate(
            ApiTokenRequirement tokenConfig,
            Map<String, String> headers,
            Map<String, String> queryParams);

    /**
     * Accepts any non-blank token in the configured location (dev / open gateways).
     */
    static ProtoApiTokenValidator acceptNonBlank() {
        return (tokenConfig, headers, queryParams) -> {
            String value = extract(tokenConfig, headers, queryParams);
            if (value == null || value.isBlank()) {
                return Optional.of("Missing API token '" + tokenConfig.name() + "'");
            }
            return Optional.empty();
        };
    }

    /**
     * Requires an exact match against {@code expectedToken}.
     */
    static ProtoApiTokenValidator sharedSecret(String expectedToken) {
        Objects.requireNonNull(expectedToken, "expectedToken");
        return (tokenConfig, headers, queryParams) -> {
            String value = extract(tokenConfig, headers, queryParams);
            if (value == null || value.isBlank()) {
                return Optional.of("Missing API token '" + tokenConfig.name() + "'");
            }
            if (tokenConfig.scheme() == ProtoApiToken.Scheme.HTTP
                    && value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                value = value.substring(7).trim();
            }
            if (!expectedToken.equals(value)) {
                return Optional.of("Invalid API token");
            }
            return Optional.empty();
        };
    }

    private static String extract(
            ApiTokenRequirement tokenConfig,
            Map<String, String> headers,
            Map<String, String> queryParams) {
        return switch (tokenConfig.in()) {
            case HEADER -> headers.getOrDefault(tokenConfig.name().toLowerCase(), null);
            case QUERY -> queryParams.get(tokenConfig.name());
            case COOKIE -> cookieValue(headers.get("cookie"), tokenConfig.name());
        };
    }

    private static String cookieValue(String cookieHeader, String cookieName) {
        if (cookieHeader == null) {
            return null;
        }
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (pair.substring(0, eq).trim().equals(cookieName)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
