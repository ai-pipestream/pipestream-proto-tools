package ai.pipestream.proto.rest;

import ai.pipestream.proto.json.MalformedProtobufJsonException;
import ai.pipestream.proto.json.ProtobufJsonException;
import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Framework-agnostic JSON/REST → protobuf RPC gateway.
 *
 * <p>Mirrors Micronaut {@code GrpcProxyService}: {@code POST /{service}/{method}} with a JSON body.
 * Descriptor resolution for dynamic requests goes through the transcoder's
 * {@link ai.pipestream.proto.descriptors.DescriptorRegistry} (Apicurio / Confluent / classpath plugins).
 */
public final class ProtoRestGateway {

    private static final Logger LOG = LoggerFactory.getLogger(ProtoRestGateway.class);

    private final ProtoRestMethodRegistry registry;
    private final ProtobufJsonTranscoder transcoder;
    private final ProtoApiTokenValidator tokenValidator;

    public ProtoRestGateway(ProtoRestMethodRegistry registry, ProtobufJsonTranscoder transcoder) {
        this(registry, transcoder, ProtoApiTokenValidator.acceptNonBlank());
    }

    public ProtoRestGateway(
            ProtoRestMethodRegistry registry,
            ProtobufJsonTranscoder transcoder,
            ProtoApiTokenValidator tokenValidator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transcoder = Objects.requireNonNull(transcoder, "transcoder");
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
    }

    public String invoke(String serviceName, String methodName, String jsonRequest) {
        return invoke(serviceName, methodName, jsonRequest, Map.of(), Map.of());
    }

    public String invoke(
            String serviceName,
            String methodName,
            String jsonRequest,
            Map<String, String> headers,
            Map<String, String> queryParams) {
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(jsonRequest, "jsonRequest");

        Map<String, String> normalizedHeaders = normalizeHeaders(headers);
        Map<String, String> safeQuery = queryParams == null ? Map.of() : queryParams;

        if (!registry.hasService(serviceName)) {
            throw new ServiceNotFoundException(serviceName);
        }

        ProtoRestMethod method = registry.find(serviceName, methodName)
                .orElseThrow(() -> new MethodNotFoundException(serviceName, methodName));

        method.apiToken().ifPresent(token -> {
            if (token.required()) {
                tokenValidator.validate(token, normalizedHeaders, safeQuery)
                        .ifPresent(reason -> {
                            throw new UnauthorizedProtoRestException(reason);
                        });
            }
        });

        try {
            Message request = decodeRequest(method, jsonRequest);
            Message response = method.invoker().apply(request);
            if (response == null) {
                return "{}";
            }
            return transcoder.toJson(response);
        } catch (MalformedProtobufJsonException | UnauthorizedProtoRestException
                 | ServiceNotFoundException | MethodNotFoundException e) {
            throw e;
        } catch (ProtobufJsonException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error("Invocation failed for {}/{}", serviceName, methodName, e);
            throw new ProtoRestInvocationException(
                    "Failed invoking " + serviceName + "/" + methodName + ": " + e.getMessage(), e);
        }
    }

    private Message decodeRequest(ProtoRestMethod method, String jsonRequest) {
        if (method.requestType().isPresent()) {
            return transcoder.fromJson(jsonRequest, method.requestType().get());
        }
        if (method.methodDescriptor() != null) {
            Descriptor input = method.methodDescriptor().getInputType();
            return transcoder.fromJsonDynamic(jsonRequest, input);
        }
        throw new ProtoRestInvocationException(
                "Method " + method.routeKey() + " has no request type or method descriptor");
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .collect(Collectors.toUnmodifiableMap(
                        e -> e.getKey().toLowerCase(Locale.ROOT),
                        Map.Entry::getValue,
                        (a, b) -> b));
    }

    public ProtoRestMethodRegistry getRegistry() {
        return registry;
    }

    public ProtobufJsonTranscoder getTranscoder() {
        return transcoder;
    }
}
