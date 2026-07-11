package ai.pipestream.proto.index.opensearch;

import ai.pipestream.proto.helpers.TypeConverter;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.SearchEngineIndexer;
import com.google.protobuf.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenSearch-oriented document map builder.
 * Uses a shared {@link IndexingPlan} (descriptor hints); does not emit NDJSON —
 * pair with {@code pipestream-proto-tools-index-ndjson} when you need bulk lines.
 */
public final class OpenSearchDocumentMapper implements SearchEngineIndexer {
    public static final String ENGINE_ID = "opensearch";

    private final ProtoFieldMapper fieldMapper;
    private final TypeConverter typeConverter;

    public OpenSearchDocumentMapper(ProtoFieldMapper fieldMapper) {
        this(fieldMapper, new TypeConverter());
    }

    public OpenSearchDocumentMapper(ProtoFieldMapper fieldMapper, TypeConverter typeConverter) {
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper");
        this.typeConverter = Objects.requireNonNull(typeConverter, "typeConverter");
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

    /** Legacy projection API (explicit paths). Prefer {@link #map(Message, IndexingPlan)}. */
    public Map<String, Object> map(Message message, List<FieldProjection> projections) throws MappingException {
        if (projections == null || projections.isEmpty()) {
            if (message instanceof com.google.protobuf.Struct struct) {
                Map<String, Object> document = new LinkedHashMap<>();
                struct.getFieldsMap().forEach((key, value) -> document.put(key, typeConverter.fromValue(value)));
                return document;
            }
            return typeConverter.messageToStruct(message).getFieldsMap().entrySet().stream()
                    .collect(LinkedHashMap::new,
                            (map, entry) -> map.put(entry.getKey(), typeConverter.fromValue(entry.getValue())),
                            LinkedHashMap::putAll);
        }
        Map<String, Object> document = new LinkedHashMap<>();
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
