package ai.pipestream.proto.samples;

import ai.pipestream.proto.cel.CelEnvironmentFactory;
import ai.pipestream.proto.cel.CelEvaluator;
import ai.pipestream.proto.cel.CelMappingRule;
import ai.pipestream.proto.cel.CelProtoMapper;
import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.util.List;

/** Demonstrates text mapping and CEL mapping against a protobuf Struct. */
public final class CelMappingSample {
    private CelMappingSample() {
    }

    public static void main(String[] args) throws Exception {
        Struct.Builder message = Struct.newBuilder()
                .putFields("name", Value.newBuilder().setStringValue("Ada").build())
                .putFields("enabled", Value.newBuilder().setBoolValue(true).build());
        ProtoFieldMapperImpl fieldMapper = new ProtoFieldMapperImpl(new DescriptorRegistry());
        fieldMapper.mapInPlace(message, List.of("copiedName = name"));

        CelEvaluator evaluator = new CelEvaluator(CelEnvironmentFactory.builder()
                .addMessageType(Struct.getDescriptor())
                .addVar("input")
                .build());
        new CelProtoMapper(fieldMapper, evaluator).map(message, List.of(
                new CelMappingRule("input.fields['enabled'].bool_value", "input.fields['name'].string_value", "selectedName")));
        System.out.println(message.build());
    }
}
