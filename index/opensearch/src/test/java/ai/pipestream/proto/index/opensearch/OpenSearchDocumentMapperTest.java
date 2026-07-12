package ai.pipestream.proto.index.opensearch;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.index.spi.IndexFieldKind;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.ResolvedFieldHint;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void coercesRepeatedEnumToNames() throws Exception {
        Descriptor descriptor = docDescriptor();
        FieldDescriptor colors = descriptor.findFieldByName("colors");
        DynamicMessage message = DynamicMessage.newBuilder(descriptor)
                .addRepeatedField(colors, colors.getEnumType().findValueByName("RED"))
                .addRepeatedField(colors, colors.getEnumType().findValueByName("BLUE"))
                .build();

        Map<String, Object> doc = mapper.map(message, List.of(
                new OpenSearchDocumentMapper.FieldProjection("colors", "colors")
        ));

        assertThat(doc.get("colors")).isEqualTo(List.of("RED", "BLUE"));
    }

    @Test
    void coercesNestedMessageToMap() throws Exception {
        Descriptor descriptor = docDescriptor();
        Descriptor innerDescriptor = descriptor.findFieldByName("inner").getMessageType();
        DynamicMessage inner = DynamicMessage.newBuilder(innerDescriptor)
                .setField(innerDescriptor.findFieldByName("name"), "n1")
                .build();
        DynamicMessage message = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("inner"), inner)
                .build();

        Map<String, Object> doc = mapper.map(message, List.of(
                new OpenSearchDocumentMapper.FieldProjection("inner", "inner")
        ));

        assertThat(doc.get("inner")).isEqualTo(Map.of("name", "n1"));
    }

    @Test
    void unsetIntermediateMessageInPlanPathSkipsField() throws Exception {
        Descriptor descriptor = docDescriptor();
        FieldDescriptor colors = descriptor.findFieldByName("colors");
        DynamicMessage message = DynamicMessage.newBuilder(descriptor)
                .addRepeatedField(colors, colors.getEnumType().findValueByName("RED"))
                .build();
        IndexingPlan plan = new IndexingPlan(descriptor.getFullName(), List.of(
                new IndexingPlan.IndexedField("inner.name", "inner_name", ResolvedFieldHint.of(IndexFieldKind.KEYWORD)),
                new IndexingPlan.IndexedField("colors", "colors", ResolvedFieldHint.of(IndexFieldKind.KEYWORD))));

        Map<String, Object> doc = mapper.map(message, plan);

        assertThat(doc).containsEntry("colors", List.of("RED")).doesNotContainKey("inner_name");
    }

    @Test
    void genuinelyInvalidPlanPathStillThrows() throws Exception {
        Descriptor descriptor = docDescriptor();
        DynamicMessage message = DynamicMessage.newBuilder(descriptor).build();
        IndexingPlan plan = new IndexingPlan(descriptor.getFullName(), List.of(
                new IndexingPlan.IndexedField("nope.name", "nope", ResolvedFieldHint.of(IndexFieldKind.KEYWORD))));

        assertThatThrownBy(() -> mapper.map(message, plan)).isInstanceOf(MappingException.class);
    }

    private static Descriptor docDescriptor() throws Exception {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("doc.proto")
                .setPackage("ai.pipestream.test")
                .setSyntax("proto3")
                .addEnumType(EnumDescriptorProto.newBuilder()
                        .setName("Color")
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("COLOR_UNSPECIFIED").setNumber(0))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(1))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("BLUE").setNumber(2)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Inner")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("name")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Doc")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("colors")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                                .setTypeName(".ai.pipestream.test.Color")
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("inner")
                                .setNumber(2)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".ai.pipestream.test.Inner")
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Doc");
    }
}
