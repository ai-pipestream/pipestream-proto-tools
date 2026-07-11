package ai.pipestream.proto.index.spi;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Struct;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingPlanFactoryTest {

    @Test
    void infersKeywordForIdAndTextForBody() throws Exception {
        Descriptor descriptor = sampleDescriptor();
        IndexingPlan plan = IndexingPlanFactory.inferringOnly().create(descriptor);

        assertThat(plan.find("doc_id")).get().extracting(f -> f.type())
                .isEqualTo(IndexFieldKind.KEYWORD);
        assertThat(plan.find("title")).get().extracting(f -> f.type())
                .isEqualTo(IndexFieldKind.TEXT);
        assertThat(plan.find("page_count")).get().extracting(f -> f.type())
                .isEqualTo(IndexFieldKind.INT32);
    }

    @Test
    void catalogOverridesInference() throws Exception {
        Descriptor descriptor = sampleDescriptor();
        CatalogIndexingHintSource catalog = new CatalogIndexingHintSource()
                .put(descriptor.getFullName(), "title", ResolvedFieldHint.of(IndexFieldKind.KEYWORD));
        IndexingPlan plan = IndexingPlanFactory.defaults(catalog).create(descriptor);

        assertThat(plan.find("title")).get().extracting(IndexingPlan.IndexedField::type)
                .isEqualTo(IndexFieldKind.KEYWORD);
    }

    @Test
    void structInfersObjectFields() {
        IndexingPlan plan = IndexingPlanFactory.inferringOnly().create(Struct.getDescriptor());
        assertThat(plan.fields()).isNotEmpty();
        assertThat(plan.find("fields")).isPresent();
    }

    private static Descriptor sampleDescriptor() throws Exception {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("doc.proto")
                .setPackage("ai.pipestream.test")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Doc")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("doc_id")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("title")
                                .setNumber(2)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("page_count")
                                .setNumber(3)
                                .setType(FieldDescriptorProto.Type.TYPE_INT32)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Doc");
    }
}
