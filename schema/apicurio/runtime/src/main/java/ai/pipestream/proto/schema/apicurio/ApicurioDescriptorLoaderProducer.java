package ai.pipestream.proto.schema.apicurio;

import ai.pipestream.proto.descriptors.DescriptorLoader;
import io.apicurio.registry.client.RegistryClientFactory;
import io.apicurio.registry.client.common.RegistryClientOptions;
import io.apicurio.registry.rest.client.RegistryClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
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

    /**
     * {@code @Dependent} so a {@code null} product is legal when the loader is disabled or
     * no registry URL is configured; consumers must treat the client as optional.
     */
    @Produces
    @Dependent
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

    /**
     * Never returns {@code null} (forbidden for non-{@code @Dependent} producers): when the
     * loader is disabled or unconfigured, produces an unavailable loader so the extension
     * degrades gracefully instead of failing injection.
     */
    @Produces
    @Singleton
    public DescriptorLoader produceApicurioDescriptorLoader(
            RegistryClient client, ProtoToolsApicurioConfig config) {
        String groupId = config.groupId();
        if (client == null || !config.enabled()) {
            LOG.warn("Apicurio descriptor loading is disabled or unconfigured; "
                    + "producing an unavailable loader (group={})", groupId);
            return new ApicurioDescriptorLoader((RegistryClient) null, groupId);
        }

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
