package ai.pipestream.proto.schema.apicurio;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApicurioProtobufParseFallbackTest {

    @Test
    void parsesPayloadAfterMagicAndContentId() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("hello").build())
                .build();
        byte[] wire = wrapWithMagicAndId(message.toByteArray(), 4);

        ApicurioProtobufParseFallback fallback = ApicurioProtobufParseFallback.forType(Struct.class);
        Struct parsed = fallback.parse(wire);
        assertThat(parsed.getFieldsOrThrow("title").getStringValue()).isEqualTo("hello");
    }

    @Test
    void parsesRawPayloadWithoutMagic() {
        FileDescriptorProto fdp = FileDescriptorProto.newBuilder().setName("x.proto").build();
        ApicurioProtobufParseFallback fallback =
                new ApicurioProtobufParseFallback(FileDescriptorProto.class, 4, false, false);
        FileDescriptorProto parsed = fallback.parse(fdp.toByteArray());
        assertThat(parsed.getName()).isEqualTo("x.proto");
    }

    @Test
    void parsesRawPayloadWithoutMagicEvenWhenIndexAndTypeRefReadingEnabled() {
        Struct message = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("raw").build())
                .build();
        ApicurioProtobufParseFallback fallback =
                new ApicurioProtobufParseFallback(Struct.class, 4, true, true);
        Struct parsed = fallback.parse(message.toByteArray());
        assertThat(parsed.getFieldsOrThrow("title").getStringValue()).isEqualTo("raw");
    }

    @Test
    void parsesFramedPayloadWithZigZagEncodedMessageIndexes() throws Exception {
        Struct message = Struct.newBuilder()
                .putFields("title", Value.newBuilder().setStringValue("indexed").build())
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ApicurioProtobufParseFallback.MAGIC_BYTE);
        out.write(ByteBuffer.allocate(4).putInt(7).array());
        // Confluent message-index list [1]: zigzag varint count=1, zigzag varint index=1
        out.write(2);
        out.write(2);
        out.write(message.toByteArray());

        ApicurioProtobufParseFallback fallback =
                new ApicurioProtobufParseFallback(Struct.class, 4, true, false);
        Struct parsed = fallback.parse(out.toByteArray());
        assertThat(parsed.getFieldsOrThrow("title").getStringValue()).isEqualTo("indexed");
    }

    @Test
    void rejectsDynamicMessage() {
        assertThatThrownBy(() ->
                ApicurioProtobufParseFallback.forType(com.google.protobuf.DynamicMessage.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DynamicMessage");
    }

    @Test
    void nullDataReturnsNull() {
        Struct parsed = ApicurioProtobufParseFallback.forType(Struct.class).parse(null);
        assertThat(parsed).isNull();
    }

    private static byte[] wrapWithMagicAndId(byte[] payload, int idSize) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ApicurioProtobufParseFallback.MAGIC_BYTE);
        out.write(ByteBuffer.allocate(idSize).putInt(42).array(), 0, idSize);
        // empty type-ref: length-delimited empty message (varint 0)
        out.write(0);
        out.write(payload);
        return out.toByteArray();
    }
}
