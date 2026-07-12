package ai.pipestream.proto.schema.apicurio;

import ai.pipestream.proto.descriptors.DescriptorLoader;
import io.apicurio.registry.rest.client.RegistryClient;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApicurioDescriptorLoaderProducerTest {

    private final ApicurioDescriptorLoaderProducer producer = new ApicurioDescriptorLoaderProducer();

    @Test
    void disabledConfigYieldsUnavailableLoaderInsteadOfNull() {
        ProtoToolsApicurioConfig config = config(false, Optional.empty());
        DescriptorLoader loader = producer.produceApicurioDescriptorLoader(null, config);
        assertThat(loader).isNotNull();
        assertThat(loader.isAvailable()).isFalse();
    }

    @Test
    void missingRegistryUrlYieldsUnavailableLoaderInsteadOfNull() {
        ProtoToolsApicurioConfig config = config(true, Optional.of(" "));
        assertThat(producer.produceRegistryClient(config)).isNull();
        DescriptorLoader loader = producer.produceApicurioDescriptorLoader(null, config);
        assertThat(loader).isNotNull();
        assertThat(loader.isAvailable()).isFalse();
    }

    @Test
    void registryClientProducerIsDependentScopedSoNullIsPermitted() throws Exception {
        Method method = ApicurioDescriptorLoaderProducer.class.getMethod(
                "produceRegistryClient", ProtoToolsApicurioConfig.class);
        assertThat(method.isAnnotationPresent(Dependent.class))
                .as("a producer that can return null must be @Dependent")
                .isTrue();
        assertThat(method.isAnnotationPresent(Singleton.class)).isFalse();
    }

    @Test
    void descriptorLoaderProducerNeverReturnsNull() {
        assertThat(producer.produceApicurioDescriptorLoader((RegistryClient) null, config(true, Optional.empty())))
                .isNotNull();
    }

    private static ProtoToolsApicurioConfig config(boolean enabled, Optional<String> registryUrl) {
        return new ProtoToolsApicurioConfig() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public Optional<String> registryUrl() {
                return registryUrl;
            }

            @Override
            public String groupId() {
                return "default";
            }

            @Override
            public boolean autoLoadOnStartup() {
                return false;
            }
        };
    }
}
