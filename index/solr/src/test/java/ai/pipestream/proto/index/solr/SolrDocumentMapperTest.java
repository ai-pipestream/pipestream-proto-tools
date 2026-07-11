package ai.pipestream.proto.index.solr;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SolrDocumentMapperTest {

    private final SolrDocumentMapper mapper =
            new SolrDocumentMapper(new ProtoFieldMapperImpl(new DescriptorRegistry()));

    @Test
    void projectsSelectedPaths() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("id", Value.newBuilder().setStringValue("doc-1").build())
                .putFields("title", Value.newBuilder().setStringValue("Hello").build())
                .build();
        Map<String, Object> doc = mapper.map(message, List.of(
                new SolrDocumentMapper.FieldProjection("id", "id"),
                new SolrDocumentMapper.FieldProjection("title", "title_s")
        ));
        assertThat(doc).containsEntry("id", "doc-1").containsEntry("title_s", "Hello");
    }

    @Test
    void nullProjectionsYieldEmptyMap() throws Exception {
        assertThat(mapper.map(Struct.getDefaultInstance(), (List<SolrDocumentMapper.FieldProjection>) null)).isEmpty();
    }

    @Test
    void skipsMissingPaths() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("id", Value.newBuilder().setStringValue("1").build())
                .build();
        Map<String, Object> doc = mapper.map(message, List.of(
                new SolrDocumentMapper.FieldProjection("nope", "n")
        ));
        assertThat(doc).isEmpty();
    }
}
