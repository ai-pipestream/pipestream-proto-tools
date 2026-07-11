package ai.pipestream.proto.server;

import ai.pipestream.proto.json.MalformedProtobufJsonException;
import ai.pipestream.proto.json.ProtobufJsonException;
import ai.pipestream.proto.rest.MethodNotFoundException;
import ai.pipestream.proto.rest.ProtoRestException;
import ai.pipestream.proto.rest.ServiceNotFoundException;
import ai.pipestream.proto.rest.UnauthorizedProtoRestException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared HTTP helpers for all server hosts — status mapping, path parse, query/header flatten.
 */
public final class ProtoRestHttpSupport {

    private ProtoRestHttpSupport() {
    }

    public static boolean isAllowedHttpMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    /**
     * @return {@code [service, method]} or empty if the path is not {@code prefix/service/method}
     */
    public static Optional<String[]> parseServiceMethod(String path, String restPathPrefix) {
        if (path == null || !path.startsWith(restPathPrefix)) {
            return Optional.empty();
        }
        String remainder = path.substring(restPathPrefix.length());
        if (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        String[] parts = remainder.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parts);
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return out;
    }

    public static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        Map<String, String> out = new HashMap<>();
        if (headers == null) {
            return out;
        }
        headers.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k.toLowerCase(Locale.ROOT), v);
            }
        });
        return out;
    }

    public static int statusFor(Throwable err) {
        Throwable cause = unwrap(err);
        if (cause instanceof UnauthorizedProtoRestException) {
            return 401;
        }
        if (cause instanceof ServiceNotFoundException || cause instanceof MethodNotFoundException) {
            return 404;
        }
        if (cause instanceof MalformedProtobufJsonException || cause instanceof ProtobufJsonException) {
            return 400;
        }
        if (cause instanceof ProtoRestException) {
            return 500;
        }
        return 500;
    }

    public static String errorJson(Throwable err) {
        Throwable cause = unwrap(err);
        int status = statusFor(cause);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return "{\"error\":" + jsonString(message) + ",\"status\":" + status + "}";
    }

    public static Throwable unwrap(Throwable err) {
        Throwable walk = err;
        while (walk != null) {
            if (walk instanceof ProtoRestException || walk instanceof ProtobufJsonException) {
                return walk;
            }
            walk = walk.getCause();
        }
        return err;
    }

    public static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
