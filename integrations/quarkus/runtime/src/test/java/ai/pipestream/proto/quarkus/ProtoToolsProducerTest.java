package ai.pipestream.proto.quarkus;

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
}
