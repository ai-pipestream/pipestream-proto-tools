package ai.pipestream.proto.index.spi;

import ai.pipestream.proto.index.hints.FieldIndexHint;
import ai.pipestream.proto.index.hints.IndexingHintsProto;
import com.google.protobuf.DescriptorProtos;
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
    void protoOptionHintWithoutTypeKeepsExplicitAttributes() throws Exception {
        Descriptor descriptor = hintedDescriptor();
        IndexingPlanFactory factory = new IndexingPlanFactory(
                new ProtoOptionsIndexingHintSource().orElse(new InferringIndexingHintSource()));
        IndexingPlan plan = factory.create(descriptor);

        IndexingPlan.IndexedField title = plan.find("title").orElseThrow();
        // explicit name/stored from the option survive even though type was left unset
        assertThat(title.fieldName()).isEqualTo("custom_title");
        assertThat(title.stored()).isFalse();
        // type comes from inference; indexed was not explicitly set, so inferred default applies
        assertThat(title.type()).isEqualTo(IndexFieldKind.TEXT);
        assertThat(title.indexed()).isTrue();
    }

    @Test
    void structInfersObjectFields() {
        IndexingPlan plan = IndexingPlanFactory.inferringOnly().create(Struct.getDescriptor());
        assertThat(plan.fields()).isNotEmpty();
        assertThat(plan.find("fields")).isPresent();
    }

    private static Descriptor hintedDescriptor() throws Exception {
        DescriptorProtos.FieldOptions titleOptions = DescriptorProtos.FieldOptions.newBuilder()
                .setExtension(IndexingHintsProto.index, FieldIndexHint.newBuilder()
                        .setName("custom_title")
                        .setStored(false)
                        .build())
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("hinted_doc.proto")
                .setPackage("ai.pipestream.test")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("HintedDoc")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("title")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setOptions(titleOptions)))
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("HintedDoc");
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
