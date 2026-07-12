package ai.pipestream.proto.schema.confluent;

/**
 * Runs the Confluent-compat suite against Apicurio Registry's ccompat v7 facade.
 *
 * <p>Override with {@code -Dpipestream.it.apicurio.url=...} or env
 * {@code PIPESTREAM_IT_APICURIO_URL} (default {@code http://localhost:18780}); the
 * {@code /apis/ccompat/v7} path is appended here.</p>
 */
class ApicurioCcompatIntegrationTest extends AbstractConfluentCompatIntegrationTest {

    @Override
    String registryBaseUrl() {
        return configuredUrl("pipestream.it.apicurio.url", "PIPESTREAM_IT_APICURIO_URL",
                "http://localhost:18780") + "/apis/ccompat/v7";
    }
}
