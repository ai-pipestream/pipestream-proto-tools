package ai.pipestream.proto.cel;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CelProtoMapperTest {
    @Test
    void appliesSelectorOnlyWhenFilterMatches() throws Exception {
        Descriptor descriptor = testFile().findMessageTypeByName("Document");
        Message.Builder target = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("title"), "source");
        CelEvaluator evaluator = new CelEvaluator(CelEnvironmentFactory.builder()
                .addMessageType(descriptor).addVar("input").build());
        CelProtoMapper mapper = new CelProtoMapper(
                new ProtoFieldMapperImpl(new DescriptorRegistry()), evaluator);

        mapper.map(target, List.of(
                new CelMappingRule("input.title == 'source'", "input.title + '-mapped'", "output"),
                new CelMappingRule("input.title == 'other'", "'ignored'", "output")));

        assertEquals("source-mapped", target.build().getField(descriptor.findFieldByName("output")));
    }

    private static FileDescriptor testFile() throws Exception {
        DescriptorProtos.DescriptorProto document = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Document")
                .addField(field("title", 1))
                .addField(field("output", 2))
                .build();
        return FileDescriptor.buildFrom(DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("cel.proto").setPackage("ai.pipestream.test").addMessageType(document).build(),
                new FileDescriptor[]{});
    }

    private static DescriptorProtos.FieldDescriptorProto field(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING).build();
    }
}
