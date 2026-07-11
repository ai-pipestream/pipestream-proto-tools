package ai.pipestream.proto.index.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * Engine-agnostic field indexing hint (Lucene-aligned vocabulary).
 * NDJSON encoding does not consume this — only search-engine plugins do.
 */
public record ResolvedFieldHint(
        IndexFieldKind type,
        boolean stored,
        boolean indexed,
        String name,
        int vectorDims) {

    public ResolvedFieldHint {
        Objects.requireNonNull(type, "type");
        name = name == null ? "" : name;
        if (vectorDims < 0) {
            throw new IllegalArgumentException("vectorDims must be >= 0");
        }
    }

    public static ResolvedFieldHint of(IndexFieldKind type) {
        boolean indexed = type != IndexFieldKind.SKIP && type != IndexFieldKind.BINARY;
        boolean stored = type != IndexFieldKind.SKIP;
        return new ResolvedFieldHint(type, stored, indexed, "", 0);
    }

    public static ResolvedFieldHint skipped() {
        return new ResolvedFieldHint(IndexFieldKind.SKIP, false, false, "", 0);
    }

    public Optional<String> nameOverride() {
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    public boolean isSkip() {
        return type == IndexFieldKind.SKIP;
    }
}
