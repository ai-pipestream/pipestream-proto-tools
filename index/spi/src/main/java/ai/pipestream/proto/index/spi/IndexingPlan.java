package ai.pipestream.proto.index.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine-agnostic indexing plan derived from a message descriptor + hints.
 * Lucene / OpenSearch / Solr plugins interpret {@link IndexFieldKind}; NDJSON ignores this.
 */
public record IndexingPlan(String messageFullName, List<IndexedField> fields) {
    public IndexingPlan {
        Objects.requireNonNull(messageFullName, "messageFullName");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    public Optional<IndexedField> find(String path) {
        return fields.stream().filter(f -> f.path().equals(path)).findFirst();
    }

    public List<IndexedField> indexable() {
        return fields.stream().filter(f -> !f.hint().isSkip()).toList();
    }

    /**
     * @param path protobuf path (dot-separated)
     * @param fieldName name written to the engine document
     * @param hint resolved indexing hint
     */
    public record IndexedField(String path, String fieldName, ResolvedFieldHint hint) {
        public IndexedField {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(hint, "hint");
        }

        public IndexFieldKind type() {
            return hint.type();
        }

        public boolean stored() {
            return hint.stored();
        }

        public boolean indexed() {
            return hint.indexed();
        }
    }
}
