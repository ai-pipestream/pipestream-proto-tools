package ai.pipestream.proto.index.solr;

import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.SearchEngineIndexer;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import com.google.protobuf.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Solr-oriented document map builder (SolrInputDocument-compatible {@link Map}).
 * Uses a shared {@link IndexingPlan} from descriptor indexing hints.
 */
public final class SolrDocumentMapper implements SearchEngineIndexer {
    public static final String ENGINE_ID = "solr";

    private final ProtoFieldMapper fieldMapper;

    public SolrDocumentMapper(ProtoFieldMapper fieldMapper) {
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper");
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public Map<String, Object> map(Message message, IndexingPlan plan) throws MappingException {
        Objects.requireNonNull(plan, "plan");
        Map<String, Object> document = new LinkedHashMap<>();
        for (IndexingPlan.IndexedField field : plan.indexable()) {
            Object value = fieldMapper.getValue(message, field.path());
            if (value != null) {
                document.put(field.fieldName(), coerce(value));
            }
        }
        return document;
    }

    /** Legacy projection API. Prefer {@link #map(Message, IndexingPlan)}. */
    public Map<String, Object> map(Message message, List<FieldProjection> projections) throws MappingException {
        Map<String, Object> document = new LinkedHashMap<>();
        if (projections == null) {
            return document;
        }
        for (FieldProjection projection : projections) {
            Object value = fieldMapper.getValue(message, projection.path());
            if (value != null) {
                document.put(projection.fieldName(), coerce(value));
            }
        }
        return document;
    }

    private static Object coerce(Object value) {
        if (value instanceof com.google.protobuf.ByteString bytes) {
            return bytes.toByteArray();
        }
        if (value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor ev) {
            return ev.getName();
        }
        return value;
    }

    public record FieldProjection(String path, String fieldName) {
        public FieldProjection {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fieldName, "fieldName");
        }
    }
}
