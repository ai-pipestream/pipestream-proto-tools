package ai.pipestream.proto.index.spi;

/**
 * Lucene-aligned field kinds shared by all search-engine plugins.
 * Mirrors {@code IndexFieldType} in {@code indexing_hints.proto}.
 */
public enum IndexFieldKind {
    UNSPECIFIED,
    TEXT,
    KEYWORD,
    INT32,
    INT64,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    DATE,
    BINARY,
    VECTOR,
    OBJECT,
    NESTED,
    SKIP
}
