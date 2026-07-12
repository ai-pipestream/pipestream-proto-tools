package ai.pipestream.proto.metadata;

import ai.pipestream.proto.cel.CelEnvironmentFactory;
import ai.pipestream.proto.cel.CelEvaluationException;
import ai.pipestream.proto.cel.CelEvaluator;
import ai.pipestream.proto.cel.CelValidation;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import dev.cel.bundle.Cel;
import dev.cel.common.types.StructTypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/**
 * Extracts named metadata values from a protobuf message with CEL selectors.
 *
 * <p>The message descriptor is used to build a typed CEL environment for {@code input},
 * so selector compile errors (typos, unknown fields) surface eagerly before any selector
 * is evaluated. Selector failures are reported as {@link IllegalStateException}s carrying
 * the selector name and expression text.
 */
public final class MetadataExtractor {
    private final CelEvaluator evaluator;
    private final ConcurrentHashMap<Descriptor, Cel> validationEnvironments = new ConcurrentHashMap<>();

    public MetadataExtractor(CelEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    public Map<String, Object> extract(
            Descriptor descriptor, Message message, Map<String, String> selectors) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(message, "message");
        if (selectors == null || selectors.isEmpty()) {
            return Map.of();
        }
        validateSelectors(descriptor, selectors);
        Map<String, Object> bindings = Map.of("input", message);
        if (selectors.size() == 1) {
            var selector = selectors.entrySet().iterator().next();
            return Map.of(selector.getKey(), evaluate(selector.getKey(), selector.getValue(), bindings));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, java.util.concurrent.Future<Object>> futures = new LinkedHashMap<>();
            selectors.forEach((name, expression) ->
                    futures.put(name, executor.submit(() -> evaluate(name, expression, bindings))));
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : futures.entrySet()) {
                try {
                    result.put(entry.getKey(), entry.getValue().get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IllegalStateException failure) {
                        throw failure;
                    }
                    throw selectorFailure(entry.getKey(), selectors.get(entry.getKey()), e.getCause());
                } catch (Exception e) {
                    throw selectorFailure(entry.getKey(), selectors.get(entry.getKey()), e);
                }
            }
            return Map.copyOf(result);
        }
    }

    /** Compiles every selector against a typed environment so errors surface before evaluation. */
    private void validateSelectors(Descriptor descriptor, Map<String, String> selectors) {
        Cel typedEnvironment = validationEnvironments.computeIfAbsent(descriptor, d ->
                CelEnvironmentFactory.builder()
                        .addMessageType(d)
                        .addVar("input", StructTypeReference.create(d.getFullName()))
                        .build());
        selectors.forEach((name, expression) -> {
            CelValidation.Result result = CelValidation.validate(typedEnvironment, expression);
            if (!result.valid()) {
                throw new IllegalStateException("Invalid metadata selector '" + name
                        + "' (" + expression + "): " + String.join("; ", result.errors()));
            }
        });
    }

    private Object evaluate(String name, String expression, Map<String, Object> bindings) {
        try {
            return evaluator.evaluateValue(expression, bindings);
        } catch (CelEvaluationException e) {
            throw selectorFailure(name, expression, e);
        }
    }

    private static IllegalStateException selectorFailure(String name, String expression, Throwable cause) {
        return new IllegalStateException(
                "Failed to extract metadata selector '" + name + "' (" + expression + ")", cause);
    }
}
