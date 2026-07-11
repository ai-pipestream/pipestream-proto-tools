package ai.pipestream.proto.spring;

import ai.pipestream.proto.cel.CelEvaluator;
import ai.pipestream.proto.descriptors.ClasspathDescriptorLoader;
import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.descriptors.GoogleDescriptorLoader;
import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import ai.pipestream.proto.rest.ProtoApiTokenValidator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for the Pipestream protobuf tools. */
@Configuration
public class ProtoToolsAutoConfiguration {

    @Bean
    public DescriptorRegistry descriptorRegistry() {
        DescriptorRegistry registry = new DescriptorRegistry();
        registry.addLoader(new GoogleDescriptorLoader());
        registry.addLoader(new ClasspathDescriptorLoader());
        return registry;
    }

    @Bean
    public ProtoFieldMapper protoFieldMapper(DescriptorRegistry descriptorRegistry) {
        return new ProtoFieldMapperImpl(descriptorRegistry);
    }

    @Bean
    public CelEvaluator celEvaluator() {
        return new CelEvaluator();
    }

    @Bean
    public ProtobufJsonTranscoder protobufJsonTranscoder(DescriptorRegistry descriptorRegistry) {
        return new ProtobufJsonTranscoder(descriptorRegistry);
    }

    @Bean
    public ProtoRestMethodRegistry protoRestMethodRegistry() {
        return new ProtoRestMethodRegistry();
    }

    @Bean
    public ProtoRestGateway protoRestGateway(
            ProtoRestMethodRegistry protoRestMethodRegistry,
            ProtobufJsonTranscoder protobufJsonTranscoder) {
        return new ProtoRestGateway(
                protoRestMethodRegistry,
                protobufJsonTranscoder,
                ProtoApiTokenValidator.acceptNonBlank());
    }
}
