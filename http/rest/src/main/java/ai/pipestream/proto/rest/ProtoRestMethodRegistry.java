package ai.pipestream.proto.rest;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.Message;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry of protobuf RPCs exposed over JSON/REST.
 * Framework-agnostic counterpart to Micronaut's {@code GrpcServiceRegistry}.
 */
public final class ProtoRestMethodRegistry {

    private final Map<String, Map<String, ProtoRestMethod>> methods = new ConcurrentHashMap<>();

    public void register(ProtoRestMethod method) {
        methods.computeIfAbsent(method.serviceName(), k -> new ConcurrentHashMap<>())
                .put(method.methodName(), method);
    }

    public ProtoRestMethod register(
            String serviceName,
            String methodName,
            Class<? extends Message> requestType,
            Function<Message, Message> invoker) {
        ProtoRestMethod method = ProtoRestMethod.builder(serviceName, methodName, invoker)
                .requestType(requestType)
                .build();
        register(method);
        return method;
    }

    public ProtoRestMethod register(
            ServiceDescriptor service,
            MethodDescriptor method,
            Function<Message, Message> invoker,
            ApiTokenRequirement apiToken) {
        ProtoRestMethod restMethod = ProtoRestMethod.builder(service.getName(), method.getName(), invoker)
                .serviceDescriptor(service)
                .methodDescriptor(method)
                .apiToken(apiToken)
                .build();
        register(restMethod);
        return restMethod;
    }

    public Optional<ProtoRestMethod> find(String serviceName, String methodName) {
        Map<String, ProtoRestMethod> byMethod = methods.get(serviceName);
        if (byMethod == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byMethod.get(methodName));
    }

    public boolean hasService(String serviceName) {
        return methods.containsKey(serviceName);
    }

    public Collection<ProtoRestMethod> all() {
        return methods.values().stream()
                .flatMap(m -> m.values().stream())
                .toList();
    }

    public void clear() {
        methods.clear();
    }
}
