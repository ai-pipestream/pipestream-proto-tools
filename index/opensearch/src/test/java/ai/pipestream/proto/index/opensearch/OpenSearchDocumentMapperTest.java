package ai.pipestream.proto.index.opensearch;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchDocumentMapperTest {

    private final OpenSearchDocumentMapper mapper =
            new OpenSearchDocumentMapper(new ProtoFieldMapperImpl(new DescriptorRegistry()));

    @Test
    void mapsUsingIndexingPlan() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("Hello").build())
                .build();
        // Explicit projections still work; plan over Struct's map field is engine-specific.
        Map<String, Object> doc = mapper.map(message, java.util.List.of(
                new OpenSearchDocumentMapper.FieldProjection("title", "title")
        ));
        assertThat(doc).containsEntry("title", "Hello");
        assertThat(mapper.engineId()).isEqualTo("opensearch");
    }

    @Test
    void projectsSelectedPaths() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("Hello").build())
                .putFields("lang", Value.newBuilder().setStringValue("en").build())
                .build();
        Map<String, Object> doc = mapper.map(message, java.util.List.of(
                new OpenSearchDocumentMapper.FieldProjection("title", "title"),
                new OpenSearchDocumentMapper.FieldProjection("lang", "language")
        ));
        assertThat(doc).containsEntry("title", "Hello").containsEntry("language", "en");
    }
}
