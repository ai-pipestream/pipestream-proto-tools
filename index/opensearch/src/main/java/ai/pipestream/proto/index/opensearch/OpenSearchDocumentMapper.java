package ai.pipestream.proto.index.opensearch;

import ai.pipestream.proto.helpers.TypeConverter;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.SearchEngineIndexer;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.Struct;

import java.util.ArrayList;
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
            if (hasUnsetIntermediate(message, field.path())) {
                continue; // unset optional parent: no value for this field, not a mapping error
            }
            Object value = fieldMapper.getValue(message, field.path());
            if (value != null) {
                document.put(field.fieldName(), coerce(value));
            }
        }
        return document;
    }

    /**
     * True when a dotted {@code path} traverses a singular message field that is not set,
     * meaning the leaf simply has no value. Anything the walk cannot positively resolve
     * (unknown field, repeated/non-message segment, Struct keys, Any unpacking) is left to
     * the field mapper so genuine path errors still surface as {@link MappingException}s.
     */
    private static boolean hasUnsetIntermediate(Message message, String path) {
        if (path.indexOf('.') < 0) {
            return false;
        }
        MessageOrBuilder current = message;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            Descriptor descriptor = current.getDescriptorForType();
            if (descriptor.getFullName().equals(Struct.getDescriptor().getFullName())) {
                return false;
            }
            FieldDescriptor fd = descriptor.findFieldByName(parts[i]);
            if (fd == null || fd.isRepeated() || fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                return false;
            }
            if (!current.hasField(fd)) {
                return true;
            }
            if (!(current.getField(fd) instanceof MessageOrBuilder next)
                    || next.getDescriptorForType().getFullName().equals(Any.getDescriptor().getFullName())) {
                return false;
            }
            current = next;
        }
        return false;
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

    private Object coerce(Object value) {
        if (value instanceof List<?> values) {
            List<Object> coerced = new ArrayList<>(values.size());
            for (Object element : values) {
                coerced.add(coerce(element));
            }
            return coerced;
        }
        if (value instanceof com.google.protobuf.ByteString bytes) {
            return bytes.toByteArray();
        }
        if (value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor ev) {
            return ev.getName();
        }
        if (value instanceof com.google.protobuf.Struct struct) {
            return structToMap(struct);
        }
        if (value instanceof Message message) {
            return structToMap(typeConverter.messageToStruct(message));
        }
        return value;
    }

    private Map<String, Object> structToMap(com.google.protobuf.Struct struct) {
        Map<String, Object> map = new LinkedHashMap<>();
        struct.getFieldsMap().forEach((key, value) -> map.put(key, toPlain(typeConverter.fromValue(value))));
        return map;
    }

    private Object toPlain(Object value) {
        if (value instanceof com.google.protobuf.Struct struct) {
            return structToMap(struct);
        }
        if (value instanceof List<?> values) {
            List<Object> plain = new ArrayList<>(values.size());
            for (Object element : values) {
                plain.add(toPlain(element));
            }
            return plain;
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
