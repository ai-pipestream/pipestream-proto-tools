package ai.pipestream.proto.indexing;

import ai.pipestream.proto.index.spi.IndexFieldKind;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.indexing.testdata.IndexableDoc;
import ai.pipestream.proto.validate.ProtoValidator;
import ai.pipestream.proto.validate.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtobufIndexerTest {

    @Test
    void buildsPlanFromIndexingHints() {
        ProtobufIndexer indexer = ProtobufIndexer.defaults(null);
        IndexingPlan plan = indexer.plan(IndexableDoc.getDescriptor());

        assertThat(plan.find("doc_id")).get().extracting(f -> f.type())
                .isEqualTo(IndexFieldKind.KEYWORD);
        assertThat(plan.find("title")).get().extracting(f -> f.type())
                .isEqualTo(IndexFieldKind.TEXT);
        assertThat(plan.find("page_count")).get().extracting(f -> f.type())
                .isEqualTo(IndexFieldKind.INT32);
    }

    @Test
    void writesNdjsonWithoutValidation() {
        ProtobufIndexer indexer = ProtobufIndexer.create();
        IndexableDoc doc = IndexableDoc.newBuilder()
                .setDocId("d1")
                .setTitle("Hello")
                .setPageCount(3)
                .build();

        String line = indexer.toNdjsonLine(doc);
        assertThat(line).contains("\"doc_id\":\"d1\"").contains("\"title\":\"Hello\"");
    }

    @Test
    void chainsValidationBeforeNdjson() {
        ProtoValidator validator = ProtoValidator.forMessageType(IndexableDoc.getDescriptor());
        ProtobufIndexer indexer = ProtobufIndexer.defaults(validator);

        IndexableDoc bad = IndexableDoc.newBuilder().setTitle("x").setPageCount(-1).build();
        assertThatThrownBy(() -> indexer.toNdjsonLine(bad))
                .isInstanceOf(ValidationResult.ValidationException.class);

        IndexableDoc ok = IndexableDoc.newBuilder()
                .setDocId("d1")
                .setTitle("Hello")
                .setPageCount(2)
                .build();
        assertThat(indexer.validate(ok).valid()).isTrue();
        assertThat(indexer.toNdjsonLine(ok)).contains("\"doc_id\":\"d1\"");
    }

    @Test
    void writeBulkIndexProducesTwoLines() {
        ProtoValidator validator = ProtoValidator.forMessageType(IndexableDoc.getDescriptor());
        ProtobufIndexer indexer = ProtobufIndexer.defaults(validator);
        IndexableDoc doc = IndexableDoc.newBuilder()
                .setDocId("d1")
                .setTitle("Hello")
                .setPageCount(1)
                .build();

        StringBuilder out = new StringBuilder();
        indexer.writeBulkIndex(out, "docs", "d1", doc);
        String[] lines = out.toString().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("\"_index\":\"docs\"").contains("\"_id\":\"d1\"");
        assertThat(lines[1]).contains("\"title\":\"Hello\"");
    }
}
