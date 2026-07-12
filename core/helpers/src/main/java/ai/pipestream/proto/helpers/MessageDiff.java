package ai.pipestream.proto.helpers;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.MessageOrBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Field-level diff between two messages of the same descriptor.
 */
public final class MessageDiff {

    private MessageDiff() {}

    public record FieldChange(String path, Object left, Object right) {}

    public static List<FieldChange> diff(MessageOrBuilder left, MessageOrBuilder right) {
        if (!left.getDescriptorForType().getFullName().equals(right.getDescriptorForType().getFullName())) {
            throw new IllegalArgumentException("Messages must share the same descriptor");
        }
        List<FieldChange> changes = new ArrayList<>();
        diffRecursive(left, right, "", changes);
        return changes;
    }

    private static void diffRecursive(MessageOrBuilder left, MessageOrBuilder right, String prefix, List<FieldChange> out) {
        for (FieldDescriptor field : left.getDescriptorForType().getFields()) {
            String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
            boolean leftSet = field.isRepeated() ? left.getRepeatedFieldCount(field) > 0 : left.hasField(field);
            boolean rightSet = field.isRepeated() ? right.getRepeatedFieldCount(field) > 0 : right.hasField(field);
            if (!leftSet && !rightSet) {
                continue;
            }
            Object lv = leftSet ? left.getField(field) : null;
            Object rv = rightSet ? right.getField(field) : null;
            if (field.isMapField()) {
                // Map entry lists are insertion-ordered; compare as maps so key order is irrelevant.
                if (!Objects.equals(mapEntries(lv), mapEntries(rv))) {
                    out.add(new FieldChange(path, lv, rv));
                }
            } else if (field.isRepeated() || field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                if (!Objects.equals(lv, rv)) {
                    out.add(new FieldChange(path, lv, rv));
                }
            } else if (lv instanceof MessageOrBuilder lm && rv instanceof MessageOrBuilder rm) {
                diffRecursive(lm, rm, path, out);
            } else if (!Objects.equals(lv, rv)) {
                out.add(new FieldChange(path, lv, rv));
            }
        }
        if (!left.getUnknownFields().equals(right.getUnknownFields())) {
            String path = prefix.isEmpty() ? "(unknown fields)" : prefix + ".(unknown fields)";
            out.add(new FieldChange(path, left.getUnknownFields(), right.getUnknownFields()));
        }
    }

    private static Map<Object, Object> mapEntries(Object entryList) {
        if (entryList == null) {
            return Map.of();
        }
        Map<Object, Object> entries = new HashMap<>();
        for (Object entry : (List<?>) entryList) {
            MessageOrBuilder message = (MessageOrBuilder) entry;
            FieldDescriptor keyField = message.getDescriptorForType().findFieldByNumber(1);
            FieldDescriptor valueField = message.getDescriptorForType().findFieldByNumber(2);
            entries.put(message.getField(keyField), message.getField(valueField));
        }
        return entries;
    }
}
