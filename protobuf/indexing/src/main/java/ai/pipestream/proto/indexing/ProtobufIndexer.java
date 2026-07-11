package ai.pipestream.proto.indexing;

import ai.pipestream.proto.index.ndjson.ProtoNdjsonWriter;
import ai.pipestream.proto.index.spi.CatalogIndexingHintSource;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.IndexingPlanFactory;
import ai.pipestream.proto.index.spi.ProtoOptionsIndexingHintSource;
import ai.pipestream.proto.validate.ProtoValidator;
import ai.pipestream.proto.validate.ValidationResult;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;

import java.util.Objects;
import java.util.Optional;

/**
 * Indexing facade: optional CEL validation, then plan + NDJSON projection.
 * Validation and indexing stay independent — pass a validator only when chaining.
 */
public final class ProtobufIndexer {

    private final IndexingPlanFactory planFactory;
    private final ProtoNdjsonWriter writer;
    private final ProtoValidator validator;

    public ProtobufIndexer(IndexingPlanFactory planFactory, ProtoNdjsonWriter writer) {
        this(planFactory, writer, null);
    }

    public ProtobufIndexer(
            IndexingPlanFactory planFactory, ProtoNdjsonWriter writer, ProtoValidator validator) {
        this.planFactory = Objects.requireNonNull(planFactory, "planFactory");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.validator = validator;
    }

    /** Inferring hints only — no validation. */
    public static ProtobufIndexer create() {
        return new ProtobufIndexer(IndexingPlanFactory.inferringOnly(), new ProtoNdjsonWriter());
    }

    /** Catalog → proto options → inference, with optional validation before NDJSON. */
    public static ProtobufIndexer defaults(ProtoValidator validator) {
        return new ProtobufIndexer(
                IndexingPlanFactory.defaults(new CatalogIndexingHintSource()),
                new ProtoNdjsonWriter(),
                validator);
    }

    public static void registerExtensions(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        ProtoOptionsIndexingHintSource.registerExtensions(registry);
        ValidationResult.registerExtensions(registry);
    }

    public Optional<ProtoValidator> validator() {
        return Optional.ofNullable(validator);
    }

    public IndexingPlan plan(Descriptor descriptor) {
        return planFactory.create(descriptor);
    }

    public ValidationResult validate(Message message) {
        if (validator == null) {
            return ValidationResult.ok();
        }
        return validator.validate(message);
    }

    /**
     * Validates when a validator is configured, then encodes one NDJSON line.
     */
    public String toNdjsonLine(Message message) {
        Objects.requireNonNull(message, "message");
        validate(message).throwIfInvalid();
        return writer.toJsonLine(message);
    }

    public void writeNdjsonLine(Appendable out, Message message) {
        Objects.requireNonNull(out, "out");
        validate(message).throwIfInvalid();
        writer.writeLine(out, message);
    }

    public void writeBulkIndex(Appendable out, String index, String id, Message document) {
        Objects.requireNonNull(out, "out");
        validate(document).throwIfInvalid();
        writer.writeBulkIndex(out, index, id, document);
    }
}
