package ai.pipestream.proto.quarkus;

import ai.pipestream.proto.cel.CelEvaluator;
import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethod;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.quarkus.arc.DefaultBean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoToolsProducerTest {

    @Test
    void producesCoreBeansWithNullExtraLoaders() {
        ProtoToolsProducer producer = new ProtoToolsProducer();
        DescriptorRegistry registry = producer.descriptorRegistry(null);
        assertThat(registry).isNotNull();

        ProtoFieldMapper mapper = producer.protoFieldMapper(registry);
        CelEvaluator cel = producer.celEvaluator();
        ProtobufJsonTranscoder transcoder = producer.protobufJsonTranscoder(registry);
        ProtoRestMethodRegistry methods = producer.protoRestMethodRegistry();
        ProtoRestGateway gateway = producer.protoRestGateway(methods, transcoder);

        assertThat(mapper).isNotNull();
        assertThat(cel).isNotNull();
        assertThat(transcoder).isNotNull();
        assertThat(gateway).isNotNull();

        methods.register(ProtoRestMethod.builder("EchoService", "Echo", request -> {
                    Struct in = (Struct) request;
                    String name = in.getFieldsOrDefault("name", Value.getDefaultInstance()).getStringValue();
                    return Struct.newBuilder()
                            .putFields("message", Value.newBuilder().setStringValue("hello " + name).build())
                            .build();
                })
                .requestType(Struct.class)
                .build());
        assertThat(gateway.invoke("EchoService", "Echo", "{\"name\":\"Ada\"}")).contains("hello Ada");
    }

    @Test
    void producesOverridableDefaultServerConfig() throws Exception {
        ProtoToolsProducer producer = new ProtoToolsProducer();
        assertThat(producer.protoToolsServerConfig()).isEqualTo(ProtoToolsServerConfig.defaults());
        // @DefaultBean so an application-provided ProtoToolsServerConfig producer wins.
        assertThat(ProtoToolsProducer.class.getMethod("protoToolsServerConfig")
                .isAnnotationPresent(DefaultBean.class)).isTrue();
    }
}
