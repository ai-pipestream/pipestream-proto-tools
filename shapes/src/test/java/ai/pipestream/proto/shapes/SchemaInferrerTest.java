package ai.pipestream.proto.shapes;

import ai.pipestream.proto.sources.ProtoSourceCompiler;
import ai.pipestream.proto.sources.ProtoSourceSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Struct-to-proto: a data-rich document in, a real message type out — and the inferred
 * schema must round-trip both ways: its emitted source recompiles, and the very samples it
 * was inferred from parse into it as proto3 JSON.
 */
class SchemaInferrerTest {

    private static Struct struct(String json) throws Exception {
        Struct.Builder builder = Struct.newBuilder();
        JsonFormat.parser().merge(json, builder);
        return builder.build();
    }

    @Test
    void infersTypesNestingAndArraysFromOneSample() throws Exception {
        var shape = new SchemaInferrer().infer("inferred.v1.Event", List.of(struct("""
                {"id": "e-1",
                 "count": 3,
                 "score": 1.5,
                 "active": true,
                 "user-name": "pat",
                 "tags": ["a", "b"],
                 "address": {"city": "Springfield", "zip": "12345"},
                 "items": [{"sku": "x", "qty": 2}, {"sku": "y", "qty": 5}],
                 "misc": [1, "two"],
                 "nothing": null}
                """)));
        Descriptor type = shape.type();
        assertThat(type.findFieldByName("id").getType()).isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(type.findFieldByName("count").getType()).isEqualTo(FieldDescriptor.Type.INT64);
        assertThat(type.findFieldByName("score").getType()).isEqualTo(FieldDescriptor.Type.DOUBLE);
        assertThat(type.findFieldByName("active").getType()).isEqualTo(FieldDescriptor.Type.BOOL);
        // Sanitized key keeps its original spelling for JSON round-trips.
        FieldDescriptor userName = type.findFieldByName("user_name");
        assertThat(userName.getJsonName()).isEqualTo("user-name");
        assertThat(type.findFieldByName("tags").isRepeated()).isTrue();
        assertThat(type.findFieldByName("tags").getType()).isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(type.findFieldByName("address").getMessageType().getName()).isEqualTo("Address");
        FieldDescriptor items = type.findFieldByName("items");
        assertThat(items.isRepeated()).isTrue();
        assertThat(items.getMessageType().findFieldByName("qty").getType())
                .isEqualTo(FieldDescriptor.Type.INT64);
        // Genuinely dynamic content falls back to Value instead of guessing.
        assertThat(type.findFieldByName("misc").getMessageType().getFullName())
                .isEqualTo("google.protobuf.Value");
        assertThat(type.findFieldByName("nothing").getMessageType().getFullName())
                .isEqualTo("google.protobuf.Value");
    }

    @Test
    void multipleSamplesUnionKeysAndWidenNumbers() throws Exception {
        var shape = new SchemaInferrer().infer("inferred.v1.Reading", List.of(
                struct("{\"sensor\": \"a\", \"value\": 3}"),
                struct("{\"sensor\": \"b\", \"value\": 3.7, \"unit\": \"C\"}")));
        Descriptor type = shape.type();
        // Integral in one sample, fractional in another: the honest type is double.
        assertThat(type.findFieldByName("value").getType())
                .isEqualTo(FieldDescriptor.Type.DOUBLE);
        assertThat(type.findFieldByName("unit")).isNotNull();
    }

    @Test
    void inferredSchemaRoundTripsSourceAndData() throws Exception {
        String sample = """
                {"id": "e-1", "count": 3,
                 "address": {"city": "Springfield"},
                 "user-name": "pat"}
                """;
        var shape = new SchemaInferrer().infer("inferred.v1.Event", List.of(struct(sample)));

        // The emitted source recompiles on its own (no imports needed here).
        var compiled = new ProtoSourceCompiler().compile(ProtoSourceSet.builder()
                .add(shape.file().getName(), shape.protoSource(), "inferred").build());
        Descriptor recompiled = compiled.descriptorFor(shape.file().getName()).orElseThrow()
                .findMessageTypeByName("Event");
        // Wire's encoder drops json_name on text recompilation; the linked descriptor
        // set (the registrable artifact) carries it — asserted below on the shape.
        assertThat(recompiled.findFieldByName("user_name")).isNotNull();
        assertThat(shape.protoSource()).contains("json_name = \"user-name\"");

        // The very sample it was inferred from parses into the inferred type.
        DynamicMessage.Builder message = DynamicMessage.newBuilder(shape.type());
        JsonFormat.parser().merge(sample, message);
        DynamicMessage built = message.build();
        assertThat(built.getField(shape.type().findFieldByName("id"))).isEqualTo("e-1");
        assertThat(built.getField(shape.type().findFieldByName("count"))).isEqualTo(3L);
        DynamicMessage address = (DynamicMessage) built.getField(
                shape.type().findFieldByName("address"));
        assertThat(address.getField(address.getDescriptorForType().findFieldByName("city")))
                .isEqualTo("Springfield");
        assertThat(built.getField(shape.type().findFieldByName("user_name"))).isEqualTo("pat");
    }

    @Test
    void emptyAndDegenerateInputsFailClearly() throws Exception {
        assertThatThrownBy(() -> new SchemaInferrer().infer("x.Y", List.of()))
                .hasMessageContaining("at least one sample");
        assertThatThrownBy(() -> new SchemaInferrer().infer("x.Y", List.of(struct("{}"))))
                .hasMessageContaining("no keys");
    }
}
