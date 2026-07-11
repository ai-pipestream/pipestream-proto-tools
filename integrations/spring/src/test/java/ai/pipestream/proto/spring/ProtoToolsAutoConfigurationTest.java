package ai.pipestream.proto.spring;

import ai.pipestream.proto.cel.CelEvaluator;
import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethod;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoToolsAutoConfigurationTest {

    @Test
    void wiresCoreBeansAndGateway() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(ProtoToolsAutoConfiguration.class)) {
            assertThat(ctx.getBean(DescriptorRegistry.class)).isNotNull();
            assertThat(ctx.getBean(ProtoFieldMapper.class)).isNotNull();
            assertThat(ctx.getBean(CelEvaluator.class)).isNotNull();
            assertThat(ctx.getBean(ProtobufJsonTranscoder.class)).isNotNull();

            ProtoRestMethodRegistry registry = ctx.getBean(ProtoRestMethodRegistry.class);
            registry.register(ProtoRestMethod.builder("EchoService", "Echo", request -> {
                        Struct in = (Struct) request;
                        String name = in.getFieldsOrDefault("name", Value.getDefaultInstance()).getStringValue();
                        return Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setStringValue("hello " + name).build())
                                .build();
                    })
                    .requestType(Struct.class)
                    .build());

            String json = ctx.getBean(ProtoRestGateway.class)
                    .invoke("EchoService", "Echo", "{\"name\":\"Ada\"}");
            assertThat(json).contains("hello Ada");
        }
    }
}
