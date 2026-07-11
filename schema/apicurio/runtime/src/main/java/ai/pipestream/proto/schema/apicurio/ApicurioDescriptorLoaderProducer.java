package ai.pipestream.proto.schema.apicurio;

import ai.pipestream.proto.descriptors.DescriptorLoader;
import io.apicurio.registry.client.RegistryClientFactory;
import io.apicurio.registry.client.common.RegistryClientOptions;
import io.apicurio.registry.rest.client.RegistryClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI producers for Apicurio-backed {@link DescriptorLoader}s.
 */
@ApplicationScoped
public class ApicurioDescriptorLoaderProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ApicurioDescriptorLoaderProducer.class);

    @Produces
    @Singleton
    public RegistryClient produceRegistryClient(ProtoToolsApicurioConfig config) {
        if (!config.enabled()) {
            return null;
        }

        String url = config.registryUrl().orElseGet(this::resolveRegistryUrl);
        if (url == null || url.isBlank()) {
            LOG.warn("No Apicurio Registry URL found. Descriptor loading from Apicurio is disabled.");
            return null;
        }

        LOG.info("Creating Apicurio Registry client for URL: {}", url);
        RegistryClientOptions options = RegistryClientOptions.create(url);
        return RegistryClientFactory.create(options);
    }

    @Produces
    @Singleton
    public DescriptorLoader produceApicurioDescriptorLoader(
            RegistryClient client, ProtoToolsApicurioConfig config) {
        if (client == null || !config.enabled()) {
            return null;
        }

        String groupId = config.groupId();
        LOG.info("Producing ApicurioDescriptorLoader for group: {}", groupId);
        return new ApicurioDescriptorLoader(client, groupId);
    }

    private String resolveRegistryUrl() {
        return ConfigProvider.getConfig()
                .getOptionalValue("mp.messaging.connector.smallrye-kafka.apicurio.registry.url", String.class)
                .or(() -> ConfigProvider.getConfig().getOptionalValue("apicurio.registry.url", String.class))
                .orElse(null);
    }
}
