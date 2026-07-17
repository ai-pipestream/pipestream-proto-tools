package ai.pipestream.proto.kafka.serde;

import ai.pipestream.proto.descriptors.DescriptorLoader.DescriptorLoadException;
import ai.pipestream.proto.schema.confluent.ConfluentSchemaRegistryLoader;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Schema ids and schemas from a Confluent-compatible registry, with the packaged descriptor set
 * underneath as a floor.
 *
 * <p>This is the part worth having. A serde that resolves schemas from a registry stops working
 * when the registry does, which turns a metadata service into a hard runtime dependency of every
 * producer and consumer on the cluster. But a serde that only reads a packaged descriptor set
 * cannot follow a topic whose writers evolve. So: consult the registry, and when it cannot answer,
 * fall back to the schema this deployment already packages rather than fail. The fallback is
 * announced once per serde, not per record, because a warning on every message is a second
 * outage.</p>
 *
 * <p>Correctness is not traded away for it. The fallback only supplies a schema; the message is
 * still validated against the rules that schema declares, and a frame whose index path disagrees
 * with the configured type is still refused rather than parsed as the wrong message.</p>
 */
final class SchemaIds implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaIds.class);

    private final ConfluentSchemaRegistryLoader registry;
    private final AtomicBoolean warnedFallback = new AtomicBoolean();

    private SchemaIds(ConfluentSchemaRegistryLoader registry) {
        this.registry = registry;
    }

    /** @return null when no registry is configured, which is a supported way to run */
    static SchemaIds create(String registryUrl) {
        return registryUrl == null || registryUrl.isBlank()
                ? null
                : new SchemaIds(new ConfluentSchemaRegistryLoader(URI.create(registryUrl.trim())));
    }

    /**
     * The id registered for a subject, or empty when the registry cannot say. Empty is not an
     * error: the caller stamps its configured id and carries on.
     */
    OptionalInt idForSubject(String subject) {
        try {
            OptionalInt id = registry.idForSubject(subject);
            if (id.isEmpty()) {
                warnOnce("subject " + subject + " is not registered");
            }
            return id;
        } catch (DescriptorLoadException e) {
            warnOnce("looking up subject " + subject + " failed: " + e.getMessage());
            return OptionalInt.empty();
        }
    }

    /**
     * The message a frame's id and index path name, or null when the registry cannot say.
     *
     * <p>A resolved schema that does not contain the index path is treated as unresolved rather
     * than as an error: the caller falls back to its configured type, which then either matches
     * the frame or is refused by the index-path check.</p>
     */
    Descriptor messageFor(int schemaId, List<Integer> indexPath) {
        try {
            FileDescriptor file = registry.schemaById(schemaId);
            Descriptor message = ConfluentWireFormat.messageAt(file, indexPath);
            if (message == null) {
                warnOnce("schema id " + schemaId + " has no message at index path " + indexPath);
            }
            return message;
        } catch (DescriptorLoadException e) {
            warnOnce("resolving schema id " + schemaId + " failed: " + e.getMessage());
            return null;
        }
    }

    /** Once per serde: a per-record warning during an outage is its own incident. */
    private void warnOnce(String what) {
        if (warnedFallback.compareAndSet(false, true)) {
            LOG.warn("Falling back to the packaged descriptor set because {}. Records are still "
                    + "validated against the packaged schema. This is logged once.", what);
        }
    }

    @Override
    public void close() {
        registry.close();
    }
}
