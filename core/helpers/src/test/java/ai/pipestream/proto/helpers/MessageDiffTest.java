package ai.pipestream.proto.helpers;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.Value;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageDiffTest {

    private static Descriptor person;

    @BeforeAll
    static void setUp() throws Exception {
        var proto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Person")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("name").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("age").setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64))
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("tags").setNumber(3)
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        FileDescriptor file = FileDescriptor.buildFrom(
                DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("person.proto").setPackage("diff")
                        .addMessageType(proto).build(),
                new FileDescriptor[]{});
        person = file.findMessageTypeByName("Person");
    }

    @Test
    void identicalMessagesProduceEmptyDiff() {
        var msg = DynamicMessage.newBuilder(person)
                .setField(person.findFieldByName("name"), "Ada")
                .build();
        assertThat(MessageDiff.diff(msg, msg)).isEmpty();
    }

    @Test
    void reportsChangedScalarFields() {
        var left = DynamicMessage.newBuilder(person)
                .setField(person.findFieldByName("name"), "Ada")
                .setField(person.findFieldByName("age"), 30L)
                .build();
        var right = DynamicMessage.newBuilder(person)
                .setField(person.findFieldByName("name"), "Ada")
                .setField(person.findFieldByName("age"), 31L)
                .build();
        List<MessageDiff.FieldChange> changes = MessageDiff.diff(left, right);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).path()).isEqualTo("age");
        assertThat(changes.get(0).left()).isEqualTo(30L);
        assertThat(changes.get(0).right()).isEqualTo(31L);
    }

    @Test
    void reportsRepeatedFieldChanges() {
        var left = DynamicMessage.newBuilder(person)
                .addRepeatedField(person.findFieldByName("tags"), "a")
                .build();
        var right = DynamicMessage.newBuilder(person)
                .addRepeatedField(person.findFieldByName("tags"), "a")
                .addRepeatedField(person.findFieldByName("tags"), "b")
                .build();
        assertThat(MessageDiff.diff(left, right))
                .extracting(MessageDiff.FieldChange::path)
                .containsExactly("tags");
    }

    @Test
    void structMapFieldDiffsAsWholeField() {
        Struct left = Struct.newBuilder()
                .putFields("same", Value.newBuilder().setStringValue("value").build())
                .putFields("changed", Value.newBuilder().setStringValue("before").build())
                .build();
        Struct right = Struct.newBuilder()
                .putFields("same", Value.newBuilder().setStringValue("value").build())
                .putFields("changed", Value.newBuilder().setStringValue("after").build())
                .build();
        // Struct stores entries in the map field "fields"
        assertThat(MessageDiff.diff(left, right))
                .extracting(MessageDiff.FieldChange::path)
                .contains("fields");
    }

    @Test
    void mapFieldInsertionOrderDoesNotProduceDiff() {
        Struct left = Struct.newBuilder()
                .putFields("a", Value.newBuilder().setStringValue("1").build())
                .putFields("b", Value.newBuilder().setStringValue("2").build())
                .build();
        Struct right = Struct.newBuilder()
                .putFields("b", Value.newBuilder().setStringValue("2").build())
                .putFields("a", Value.newBuilder().setStringValue("1").build())
                .build();
        assertThat(left).isEqualTo(right);
        assertThat(MessageDiff.diff(left, right)).isEmpty();
    }

    @Test
    void reportsUnknownFieldDifferences() {
        var withUnknown = DynamicMessage.newBuilder(person)
                .setField(person.findFieldByName("name"), "Ada")
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build())
                        .build())
                .build();
        var without = DynamicMessage.newBuilder(person)
                .setField(person.findFieldByName("name"), "Ada")
                .build();
        assertThat(MessageDiff.diff(without, withUnknown))
                .extracting(MessageDiff.FieldChange::path)
                .containsExactly("(unknown fields)");
    }

    @Test
    void rejectsMismatchedDescriptors() {
        assertThrows(IllegalArgumentException.class, () ->
                MessageDiff.diff(
                        DynamicMessage.newBuilder(person).build(),
                        Struct.getDefaultInstance()));
    }
}
