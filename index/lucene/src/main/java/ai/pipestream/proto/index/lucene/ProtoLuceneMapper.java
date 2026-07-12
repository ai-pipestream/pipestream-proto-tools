package ai.pipestream.proto.index.lucene;

import ai.pipestream.proto.index.spi.IndexFieldKind;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.SearchEngineIndexer;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;

import java.util.List;
import java.util.Objects;

/**
 * Lucene document mapper driven by an {@link IndexingPlan} (descriptor indexing hints).
 */
public final class ProtoLuceneMapper implements SearchEngineIndexer {
    public static final String ENGINE_ID = "lucene";

    private final ProtoFieldMapper fieldMapper;

    public ProtoLuceneMapper(ProtoFieldMapper fieldMapper) {
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper");
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public Document map(Message message, IndexingPlan plan) throws MappingException {
        Objects.requireNonNull(plan, "plan");
        Document document = new Document();
        for (IndexingPlan.IndexedField field : plan.indexable()) {
            if (hasUnsetIntermediate(message, field.path())) {
                continue; // unset optional parent: no value for this field, not a mapping error
            }
            Object value = fieldMapper.getValue(message, field.path());
            if (value == null) {
                continue;
            }
            add(document, field, value);
        }
        return document;
    }

    /** Legacy projection API. Prefer {@link #map(Message, IndexingPlan)}. */
    public Document map(Message message, List<FieldProjection> projections) throws MappingException {
        Document document = new Document();
        if (projections == null) {
            return document;
        }
        for (FieldProjection projection : projections) {
            Object value = fieldMapper.getValue(message, projection.path());
            if (value == null) {
                continue;
            }
            ResolvedLegacy legacy = new ResolvedLegacy(projection);
            add(document, legacy, value);
        }
        return document;
    }

    private void add(Document document, IndexingPlan.IndexedField field, Object value) {
        add(document, field.fieldName(), field.type(), field.stored(), field.indexed(), value);
    }

    private void add(Document document, ResolvedLegacy field, Object value) {
        IndexFieldKind kind = value instanceof String ? IndexFieldKind.TEXT : IndexFieldKind.KEYWORD;
        add(document, field.luceneFieldName(), kind, field.stored(), field.indexed(), value);
    }

    private static void add(
            Document document,
            String name,
            IndexFieldKind kind,
            boolean stored,
            boolean indexed,
            Object value) {
        if (value instanceof List<?> values) {
            for (Object element : values) {
                add(document, name, kind, stored, indexed, element);
            }
            return;
        }
        org.apache.lucene.document.Field.Store store =
                stored ? org.apache.lucene.document.Field.Store.YES : org.apache.lucene.document.Field.Store.NO;

        if (value instanceof ByteString bytes) {
            if (stored) {
                document.add(new StoredField(name, bytes.toByteArray()));
            }
            return;
        }
        if (isTimestampMessage(value)) {
            long millis = timestampMillis((MessageOrBuilder) value);
            if (indexed) {
                document.add(new LongPoint(name, millis));
            }
            if (stored) {
                document.add(new StoredField(name, millis));
            }
            return;
        }

        switch (kind) {
            case TEXT -> {
                if (indexed) {
                    document.add(new TextField(name, String.valueOf(value), store));
                } else if (stored) {
                    document.add(new StoredField(name, String.valueOf(value)));
                }
            }
            case DATE -> {
                if (value instanceof Number number) {
                    // numeric date values are epoch millis
                    long millis = number.longValue();
                    if (indexed) {
                        document.add(new LongPoint(name, millis));
                    }
                    if (stored) {
                        document.add(new StoredField(name, millis));
                    }
                } else {
                    String stringValue = String.valueOf(value);
                    if (indexed) {
                        document.add(new StringField(name, stringValue, store));
                    } else if (stored) {
                        document.add(new StoredField(name, stringValue));
                    }
                }
            }
            case KEYWORD, BOOLEAN -> {
                String stringValue = value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor ev
                        ? ev.getName()
                        : String.valueOf(value);
                if (indexed) {
                    document.add(new StringField(name, stringValue, store));
                } else if (stored) {
                    document.add(new StoredField(name, stringValue));
                }
            }
            case INT32 -> {
                int v = ((Number) value).intValue();
                if (indexed) {
                    document.add(new IntPoint(name, v));
                }
                if (stored) {
                    document.add(new StoredField(name, v));
                }
            }
            case INT64 -> {
                long v = ((Number) value).longValue();
                if (indexed) {
                    document.add(new LongPoint(name, v));
                }
                if (stored) {
                    document.add(new StoredField(name, v));
                }
            }
            case FLOAT -> {
                float v = ((Number) value).floatValue();
                if (indexed) {
                    document.add(new FloatPoint(name, v));
                }
                if (stored) {
                    document.add(new StoredField(name, v));
                }
            }
            case DOUBLE -> {
                double v = ((Number) value).doubleValue();
                if (indexed) {
                    document.add(new DoublePoint(name, v));
                }
                if (stored) {
                    document.add(new StoredField(name, v));
                }
            }
            case BINARY -> {
                if (stored && value instanceof byte[] bytes) {
                    document.add(new StoredField(name, bytes));
                }
            }
            case VECTOR, OBJECT, NESTED, UNSPECIFIED, SKIP -> {
                if (stored) {
                    document.add(new StoredField(name, String.valueOf(value)));
                }
            }
        }
    }

    /**
     * Detects google.protobuf.Timestamp values by descriptor full name, so DynamicMessage
     * instances (registry-driven workflows) are recognised alongside generated {@link Timestamp}s.
     */
    private static boolean isTimestampMessage(Object value) {
        return value instanceof MessageOrBuilder messageOrBuilder
                && messageOrBuilder.getDescriptorForType().getFullName()
                        .equals(Timestamp.getDescriptor().getFullName());
    }

    /** Epoch millis from a value recognised by {@link #isTimestampMessage(Object)}. */
    private static long timestampMillis(MessageOrBuilder value) {
        if (value instanceof Timestamp ts) {
            return ts.getSeconds() * 1000L + ts.getNanos() / 1_000_000L;
        }
        Descriptor descriptor = value.getDescriptorForType();
        long seconds = ((Number) value.getField(descriptor.findFieldByName("seconds"))).longValue();
        long nanos = ((Number) value.getField(descriptor.findFieldByName("nanos"))).longValue();
        return seconds * 1000L + nanos / 1_000_000L;
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

    private record ResolvedLegacy(FieldProjection projection) {
        String luceneFieldName() {
            return projection.luceneFieldName();
        }

        boolean stored() {
            return projection.stored();
        }

        boolean indexed() {
            return projection.indexed();
        }
    }

    public record FieldProjection(String path, String luceneFieldName, boolean stored, boolean indexed) {
        public FieldProjection {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(luceneFieldName, "luceneFieldName");
        }
    }
}
