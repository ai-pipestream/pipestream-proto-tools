package ai.pipestream.proto.quarkus;

import ai.pipestream.proto.cel.CelEvaluator;
import ai.pipestream.proto.descriptors.ClasspathDescriptorLoader;
import ai.pipestream.proto.descriptors.DescriptorLoader;
import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.descriptors.GoogleDescriptorLoader;
import ai.pipestream.proto.json.ProtobufJsonTranscoder;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import ai.pipestream.proto.rest.ProtoApiTokenValidator;
import ai.pipestream.proto.rest.ProtoRestGateway;
import ai.pipestream.proto.rest.ProtoRestMethodRegistry;
import ai.pipestream.proto.server.ProtoToolsServerConfig;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** CDI producers for the Pipestream protobuf tools runtime. */
@Singleton
public final class ProtoToolsProducer {

    @Produces
    @Singleton
    public DescriptorRegistry descriptorRegistry(Instance<DescriptorLoader> extraLoaders) {
        DescriptorRegistry registry = new DescriptorRegistry();
        registry.addLoader(new GoogleDescriptorLoader());
        registry.addLoader(new ClasspathDescriptorLoader());
        if (extraLoaders != null) {
            for (DescriptorLoader loader : extraLoaders) {
                if (loader != null && loader.isAvailable()) {
                    registry.addLoader(loader);
                }
            }
        }
        return registry;
    }

    @Produces
    @Singleton
    public ProtoFieldMapper protoFieldMapper(DescriptorRegistry descriptorRegistry) {
        return new ProtoFieldMapperImpl(descriptorRegistry);
    }

    @Produces
    @Singleton
    public CelEvaluator celEvaluator() {
        return new CelEvaluator();
    }

    @Produces
    @Singleton
    public ProtobufJsonTranscoder protobufJsonTranscoder(DescriptorRegistry descriptorRegistry) {
        return new ProtobufJsonTranscoder(descriptorRegistry);
    }

    @Produces
    @Singleton
    public ProtoRestMethodRegistry protoRestMethodRegistry() {
        return new ProtoRestMethodRegistry();
    }

    /** Default server config; apps override the REST prefix by producing their own bean. */
    @Produces
    @DefaultBean
    @Singleton
    public ProtoToolsServerConfig protoToolsServerConfig() {
        return ProtoToolsServerConfig.defaults();
    }

    @Produces
    @Singleton
    public ProtoRestGateway protoRestGateway(
            ProtoRestMethodRegistry protoRestMethodRegistry,
            ProtobufJsonTranscoder protobufJsonTranscoder) {
        return new ProtoRestGateway(
                protoRestMethodRegistry,
                protobufJsonTranscoder,
                ProtoApiTokenValidator.acceptNonBlank());
    }
}
