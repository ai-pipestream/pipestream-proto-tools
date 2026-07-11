package ai.pipestream.proto.metadata;

import ai.pipestream.proto.cel.CelEnvironmentFactory;
import ai.pipestream.proto.cel.CelEvaluator;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataExtractorTest {

    @Test
    void extractsSingleSelectorWithoutExecutor() {
        CelEvaluator evaluator = new CelEvaluator(CelEnvironmentFactory.builder()
                .addMessageType(Struct.getDescriptor()).addVar("input").build());
        Struct input = Struct.newBuilder().putFields("title", Value.newBuilder().setStringValue("Hello").build()).build();
        assertEquals("Hello", new MetadataExtractor(evaluator).extract(Struct.getDescriptor(), input,
                Map.of("title", "input.title")).get("title"));
    }

    @Test
    void wrapsMissingSelectorFailure() {
        CelEvaluator evaluator = new CelEvaluator(CelEnvironmentFactory.builder()
                .addMessageType(Struct.getDescriptor()).addVar("input").build());
        Struct input = Struct.getDefaultInstance();
        assertThrows(RuntimeException.class, () -> new MetadataExtractor(evaluator).extract(
                Struct.getDescriptor(), input, Map.of("bad", "input.")));
    }

    @Test
    void extractsNamedSelectorsInParallel() {
        CelEvaluator evaluator = new CelEvaluator(CelEnvironmentFactory.builder()
                .addMessageType(Struct.getDescriptor())
                .addVar("input")
                .build());
        MetadataExtractor extractor = new MetadataExtractor(evaluator);

        Struct input = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("Hello").build())
                .putFields("score", Value.newBuilder().setNumberValue(42).build())
                .build();

        Map<String, String> selectors = new LinkedHashMap<>();
        selectors.put("title", "input.title");
        selectors.put("score", "input.score");

        Map<String, Object> result = extractor.extract(Struct.getDescriptor(), input, selectors);

        assertThat(result).containsEntry("title", "Hello");
        assertThat(result.get("score")).isEqualTo(42.0);
    }
}
