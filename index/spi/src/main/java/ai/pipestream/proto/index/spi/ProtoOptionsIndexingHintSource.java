package ai.pipestream.proto.index.spi;

import ai.pipestream.proto.index.hints.FieldIndexHint;
import ai.pipestream.proto.index.hints.IndexFieldType;
import ai.pipestream.proto.index.hints.IndexingHintsProto;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.ExtensionRegistry;

import java.util.Optional;

/**
 * Reads {@code (ai.pipestream.proto.index.hints.v1.index)} custom options from the descriptor.
 * Requires descriptors parsed/built with {@link #registerExtensions(ExtensionRegistry)}.
 */
public final class ProtoOptionsIndexingHintSource implements IndexingHintSource {

    public static void registerExtensions(ExtensionRegistry registry) {
        IndexingHintsProto.registerAllExtensions(registry);
    }

    @Override
    public Optional<ResolvedFieldHint> resolve(FieldDescriptor field) {
        var options = field.getOptions();
        if (!options.hasExtension(IndexingHintsProto.index)) {
            return Optional.empty();
        }
        return Optional.of(toResolved(options.getExtension(IndexingHintsProto.index), field));
    }

    static ResolvedFieldHint toResolved(FieldIndexHint hint, FieldDescriptor field) {
        IndexFieldKind kind = toKind(hint.getType());
        // Type left unset → infer it from the descriptor; explicitly-set name/stored/indexed still win.
        ResolvedFieldHint defaults = kind == IndexFieldKind.UNSPECIFIED
                ? InferringIndexingHintSource.infer(field)
                : ResolvedFieldHint.of(kind);
        boolean stored = hint.hasStored() ? hint.getStored() : defaults.stored();
        boolean indexed = hint.hasIndexed() ? hint.getIndexed() : defaults.indexed();
        return new ResolvedFieldHint(defaults.type(), stored, indexed, hint.getName(), hint.getVectorDims());
    }

    private static IndexFieldKind toKind(IndexFieldType type) {
        return switch (type) {
            case INDEX_FIELD_TYPE_TEXT -> IndexFieldKind.TEXT;
            case INDEX_FIELD_TYPE_KEYWORD -> IndexFieldKind.KEYWORD;
            case INDEX_FIELD_TYPE_INT32 -> IndexFieldKind.INT32;
            case INDEX_FIELD_TYPE_INT64 -> IndexFieldKind.INT64;
            case INDEX_FIELD_TYPE_FLOAT -> IndexFieldKind.FLOAT;
            case INDEX_FIELD_TYPE_DOUBLE -> IndexFieldKind.DOUBLE;
            case INDEX_FIELD_TYPE_BOOLEAN -> IndexFieldKind.BOOLEAN;
            case INDEX_FIELD_TYPE_DATE -> IndexFieldKind.DATE;
            case INDEX_FIELD_TYPE_BINARY -> IndexFieldKind.BINARY;
            case INDEX_FIELD_TYPE_VECTOR -> IndexFieldKind.VECTOR;
            case INDEX_FIELD_TYPE_OBJECT -> IndexFieldKind.OBJECT;
            case INDEX_FIELD_TYPE_NESTED -> IndexFieldKind.NESTED;
            case INDEX_FIELD_TYPE_SKIP -> IndexFieldKind.SKIP;
            case INDEX_FIELD_TYPE_UNSPECIFIED, UNRECOGNIZED -> IndexFieldKind.UNSPECIFIED;
        };
    }
}
