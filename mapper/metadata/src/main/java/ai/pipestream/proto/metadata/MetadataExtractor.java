package ai.pipestream.proto.metadata;

import ai.pipestream.proto.cel.CelEvaluator;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/** Extracts named metadata values from a protobuf message with CEL selectors. */
public final class MetadataExtractor {
    private final CelEvaluator evaluator;

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
        Map<String, Object> bindings = Map.of("input", message);
        if (selectors.size() == 1) {
            var selector = selectors.entrySet().iterator().next();
            return Map.of(selector.getKey(), evaluator.evaluateValue(selector.getValue(), bindings));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, java.util.concurrent.Future<Object>> futures = new LinkedHashMap<>();
            selectors.forEach((name, expression) ->
                    futures.put(name, executor.submit(() -> evaluator.evaluateValue(expression, bindings))));
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : futures.entrySet()) {
                try {
                    result.put(entry.getKey(), entry.getValue().get());
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to extract metadata selector: " + entry.getKey(), e);
                }
            }
            return Map.copyOf(result);
        }
    }
}
