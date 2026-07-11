package ai.pipestream.proto.index.lucene;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoLuceneMapperTest {

    private final ProtoLuceneMapper mapper =
            new ProtoLuceneMapper(new ProtoFieldMapperImpl(new DescriptorRegistry()));

    @Test
    void projectsStructPathsIntoLuceneFields() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("Pipestream").build())
                .putFields("lang", Value.newBuilder().setStringValue("en").build())
                .build();

        Document doc = mapper.map(message, List.of(
                new ProtoLuceneMapper.FieldProjection("title", "title", true, true),
                new ProtoLuceneMapper.FieldProjection("lang", "lang", true, true)
        ));

        assertThat(doc.get("title")).isEqualTo("Pipestream");
        assertThat(doc.get("lang")).isEqualTo("en");
    }

    @Test
    void skipsNullPaths() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("only").build())
                .build();
        Document doc = mapper.map(message, List.of(
                new ProtoLuceneMapper.FieldProjection("title", "title", true, true),
                new ProtoLuceneMapper.FieldProjection("missing", "missing", true, true)
        ));
        assertThat(doc.get("title")).isEqualTo("only");
        assertThat(doc.get("missing")).isNull();
    }

    @Test
    void storedOnlyNumericField() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("score", Value.newBuilder().setNumberValue(3.5).build())
                .build();
        Document doc = mapper.map(message, List.of(
                new ProtoLuceneMapper.FieldProjection("score", "score", true, false)
        ));
        assertThat(doc.get("score")).isEqualTo("3.5");
    }

    @Test
    void emptyProjectionsYieldEmptyDocument() throws Exception {
        assertThat(mapper.map(Struct.getDefaultInstance(), List.<ProtoLuceneMapper.FieldProjection>of()).getFields()).isEmpty();
        assertThat(mapper.map(Struct.getDefaultInstance(), (List<ProtoLuceneMapper.FieldProjection>) null).getFields()).isEmpty();
    }
}
