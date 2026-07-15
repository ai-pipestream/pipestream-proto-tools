package ai.pipestream.proto.meta;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Masks fields by their schema-declared sensitivity class
 * ({@code ai.pipestream.proto.meta.v1.field.sensitivity}) — declare once in the contract,
 * mask on every surface. Two strategies:
 *
 * <ul>
 *   <li>{@code REMOVE} — the field is cleared; absent from output entirely;</li>
 *   <li>{@code REDACT} — strings become {@code ***} (visibly masked), everything else is
 *       cleared: a redacted number or bool would still leak by being plausible.</li>
 * </ul>
 *
 * <p>Recursion covers singular and repeated message fields, so a {@code pii}-classed field
 * three levels down is found. Requires descriptors whose options were parsed with the
 * metadata extensions registered ({@link DescriptorMetadata#registerExtensions}); options
 * left as unknown fields mask nothing.</p>
 */
public final class SensitivityMasker {

    public enum Strategy {
        REMOVE, REDACT;

        public static Strategy of(String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }
    }

    private static final String REDACTED = "***";

    /** The masked message plus which field paths were touched. */
    public record MaskResult(Message message, List<String> maskedPaths) {
    }

    private SensitivityMasker() {
    }

    public static MaskResult mask(Message message, Set<String> classes, Strategy strategy) {
        List<String> masked = new ArrayList<>();
        Message result = maskMessage(message, classes, strategy, "", masked);
        return new MaskResult(result, List.copyOf(masked));
    }

    private static Message maskMessage(Message message, Set<String> classes,
                                       Strategy strategy, String prefix, List<String> masked) {
        Message.Builder builder = message.toBuilder();
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
            String sensitivity = DescriptorMetadata.field(field)
                    .map(meta -> meta.getSensitivity())
                    .orElse("");
            if (!sensitivity.isEmpty() && classes.contains(sensitivity)) {
                apply(builder, field, strategy);
                masked.add(path);
                continue;
            }
            if (field.isMapField() || field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            if (field.isRepeated()) {
                int count = message.getRepeatedFieldCount(field);
                for (int i = 0; i < count; i++) {
                    builder.setRepeatedField(field, i, maskMessage(
                            (Message) message.getRepeatedField(field, i),
                            classes, strategy, path, masked));
                }
            } else if (message.hasField(field)) {
                builder.setField(field, maskMessage((Message) message.getField(field),
                        classes, strategy, path, masked));
            }
        }
        return builder.build();
    }

    private static void apply(Message.Builder builder, FieldDescriptor field,
                              Strategy strategy) {
        if (strategy == Strategy.REMOVE
                || field.getJavaType() != FieldDescriptor.JavaType.STRING) {
            builder.clearField(field);
            return;
        }
        if (field.isRepeated()) {
            int count = builder.getRepeatedFieldCount(field);
            for (int i = 0; i < count; i++) {
                builder.setRepeatedField(field, i, REDACTED);
            }
        } else {
            builder.setField(field, REDACTED);
        }
    }
}
