package ai.pipestream.proto.validate;

import ai.pipestream.proto.cel.CelEnvironmentFactory;
import ai.pipestream.proto.cel.CelEvaluationException;
import ai.pipestream.proto.cel.CelEvaluator;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates protobuf messages using {@code (ai.pipestream.proto.validate.v1.field|message)}
 * options. Standard constraints run in-process; custom rules use CEL with {@code this}.
 */
public final class ProtoValidator {

    private final CelEvaluator fieldCel;
    private final CelEvaluator messageCel;

    public ProtoValidator(CelEvaluator fieldCel, CelEvaluator messageCel) {
        this.fieldCel = Objects.requireNonNull(fieldCel, "fieldCel");
        this.messageCel = Objects.requireNonNull(messageCel, "messageCel");
    }

    /** Default CEL environments: {@code this} is DYN for field and message rules. */
    public static ProtoValidator create() {
        CelEvaluator field = new CelEvaluator(CelEnvironmentFactory.builder()
                .addVar("this")
                .build());
        CelEvaluator message = new CelEvaluator(CelEnvironmentFactory.builder()
                .addVar("this")
                .build());
        return new ProtoValidator(field, message);
    }

    /**
     * Builds a validator whose message-level CEL knows {@code descriptor}'s type
     * (field access like {@code this.age}).
     */
    public static ProtoValidator forMessageType(Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        CelEvaluator field = new CelEvaluator(CelEnvironmentFactory.builder()
                .addVar("this")
                .build());
        CelEvaluator message = new CelEvaluator(CelEnvironmentFactory.builder()
                .addMessageType(descriptor)
                .addVar("this")
                .build());
        return new ProtoValidator(field, message);
    }

    public ValidationResult validate(Message message) {
        Objects.requireNonNull(message, "message");
        List<ValidationResult.Violation> violations = new ArrayList<>();
        Descriptor descriptor = message.getDescriptorForType();
        for (FieldDescriptor field : descriptor.getFields()) {
            validateField(message, field, field.getName(), violations);
        }
        validateMessageRules(message, descriptor, "", violations);
        return violations.isEmpty()
                ? ValidationResult.ok()
                : ValidationResult.failed(violations);
    }

    private void validateField(
            Message message,
            FieldDescriptor field,
            String path,
            List<ValidationResult.Violation> violations) {
        var options = field.getOptions();
        if (!options.hasExtension(ValidateProto.field)) {
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                    && !field.isRepeated()
                    && message.hasField(field)) {
                Object value = message.getField(field);
                if (value instanceof Message nested) {
                    for (FieldDescriptor child : nested.getDescriptorForType().getFields()) {
                        validateField(nested, child, path + "." + child.getName(), violations);
                    }
                    validateMessageRules(nested, nested.getDescriptorForType(), path, violations);
                }
            }
            return;
        }
        FieldRules rules = options.getExtension(ValidateProto.field);
        boolean present = isPresent(message, field);
        if (rules.getRequired() && !present) {
            violations.add(new ValidationResult.Violation(path, "required", "field is required"));
            return;
        }
        if (!present) {
            return;
        }
        Object value = message.getField(field);
        if (field.isRepeated()) {
            // Scalar repeated: run CEL per element when configured; skip type rules for v1.
            if (value instanceof List<?> list) {
                int i = 0;
                for (Object element : list) {
                    runFieldCel(rules, element, path + "[" + i + "]", violations);
                    i++;
                }
            }
            return;
        }
        applyScalarRules(field, rules, value, path, violations);
        runFieldCel(rules, value, path, violations);
        if (value instanceof Message nested) {
            for (FieldDescriptor child : nested.getDescriptorForType().getFields()) {
                validateField(nested, child, path + "." + child.getName(), violations);
            }
            validateMessageRules(nested, nested.getDescriptorForType(), path, violations);
        }
    }

    private void applyScalarRules(
            FieldDescriptor field,
            FieldRules rules,
            Object value,
            String path,
            List<ValidationResult.Violation> violations) {
        switch (field.getJavaType()) {
            case STRING -> applyString(rules.getString(), (String) value, path, violations);
            case INT -> applyInt32(rules.getInt32(), ((Number) value).intValue(), path, violations);
            case LONG -> applyInt64(rules.getInt64(), ((Number) value).longValue(), path, violations);
            case FLOAT -> applyFloat(rules.getFloat(), ((Number) value).floatValue(), path, violations);
            case DOUBLE -> applyDouble(rules.getDouble(), ((Number) value).doubleValue(), path, violations);
            default -> {
            }
        }
    }

    private static void applyString(
            StringRules rules, String value, String path, List<ValidationResult.Violation> violations) {
        long len = value.codePointCount(0, value.length());
        if (rules.hasMinLen() && len < rules.getMinLen()) {
            violations.add(new ValidationResult.Violation(
                    path, "string.min_len", "length must be at least " + rules.getMinLen()));
        }
        if (rules.hasMaxLen() && len > rules.getMaxLen()) {
            violations.add(new ValidationResult.Violation(
                    path, "string.max_len", "length must be at most " + rules.getMaxLen()));
        }
        if (rules.hasPattern() && !rules.getPattern().isEmpty()
                && !Pattern.compile(rules.getPattern()).matcher(value).find()) {
            violations.add(new ValidationResult.Violation(
                    path, "string.pattern", "value does not match pattern"));
        }
        if (rules.getEmail() && !looksLikeEmail(value)) {
            violations.add(new ValidationResult.Violation(
                    path, "string.email", "value must look like an email address"));
        }
    }

    private static boolean looksLikeEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 1 && value.indexOf('@', at + 1) < 0;
    }

    private static void applyInt32(
            Int32Rules rules, int value, String path, List<ValidationResult.Violation> violations) {
        if (rules.hasGt() && !(value > rules.getGt())) {
            violations.add(violation(path, "int32.gt", "must be > " + rules.getGt()));
        }
        if (rules.hasGte() && !(value >= rules.getGte())) {
            violations.add(violation(path, "int32.gte", "must be >= " + rules.getGte()));
        }
        if (rules.hasLt() && !(value < rules.getLt())) {
            violations.add(violation(path, "int32.lt", "must be < " + rules.getLt()));
        }
        if (rules.hasLte() && !(value <= rules.getLte())) {
            violations.add(violation(path, "int32.lte", "must be <= " + rules.getLte()));
        }
    }

    private static void applyInt64(
            Int64Rules rules, long value, String path, List<ValidationResult.Violation> violations) {
        if (rules.hasGt() && !(value > rules.getGt())) {
            violations.add(violation(path, "int64.gt", "must be > " + rules.getGt()));
        }
        if (rules.hasGte() && !(value >= rules.getGte())) {
            violations.add(violation(path, "int64.gte", "must be >= " + rules.getGte()));
        }
        if (rules.hasLt() && !(value < rules.getLt())) {
            violations.add(violation(path, "int64.lt", "must be < " + rules.getLt()));
        }
        if (rules.hasLte() && !(value <= rules.getLte())) {
            violations.add(violation(path, "int64.lte", "must be <= " + rules.getLte()));
        }
    }

    private static void applyFloat(
            FloatRules rules, float value, String path, List<ValidationResult.Violation> violations) {
        if (rules.hasGt() && !(value > rules.getGt())) {
            violations.add(violation(path, "float.gt", "must be > " + rules.getGt()));
        }
        if (rules.hasGte() && !(value >= rules.getGte())) {
            violations.add(violation(path, "float.gte", "must be >= " + rules.getGte()));
        }
        if (rules.hasLt() && !(value < rules.getLt())) {
            violations.add(violation(path, "float.lt", "must be < " + rules.getLt()));
        }
        if (rules.hasLte() && !(value <= rules.getLte())) {
            violations.add(violation(path, "float.lte", "must be <= " + rules.getLte()));
        }
    }

    private static void applyDouble(
            DoubleRules rules, double value, String path, List<ValidationResult.Violation> violations) {
        if (rules.hasGt() && !(value > rules.getGt())) {
            violations.add(violation(path, "double.gt", "must be > " + rules.getGt()));
        }
        if (rules.hasGte() && !(value >= rules.getGte())) {
            violations.add(violation(path, "double.gte", "must be >= " + rules.getGte()));
        }
        if (rules.hasLt() && !(value < rules.getLt())) {
            violations.add(violation(path, "double.lt", "must be < " + rules.getLt()));
        }
        if (rules.hasLte() && !(value <= rules.getLte())) {
            violations.add(violation(path, "double.lte", "must be <= " + rules.getLte()));
        }
    }

    private void runFieldCel(
            FieldRules rules, Object value, String path, List<ValidationResult.Violation> violations) {
        for (CelRule rule : rules.getCelList()) {
            evalCel(fieldCel, rule, value, path, violations);
        }
    }

    private void validateMessageRules(
            Message message,
            Descriptor descriptor,
            String path,
            List<ValidationResult.Violation> violations) {
        var options = descriptor.getOptions();
        if (!options.hasExtension(ValidateProto.message)) {
            return;
        }
        MessageRules rules = options.getExtension(ValidateProto.message);
        String msgPath = path.isEmpty() ? descriptor.getName() : path;
        for (CelRule rule : rules.getCelList()) {
            evalCel(messageCel, rule, message, msgPath, violations);
        }
    }

    private static void evalCel(
            CelEvaluator evaluator,
            CelRule rule,
            Object thisValue,
            String path,
            List<ValidationResult.Violation> violations) {
        if (rule.getExpression().isBlank()) {
            return;
        }
        String id = rule.getId().isBlank() ? "cel" : rule.getId();
        try {
            Object result = evaluator.evaluateValue(rule.getExpression(), Map.of("this", thisValue));
            if (result instanceof Boolean ok) {
                if (!ok) {
                    String msg = rule.getMessage().isBlank() ? "CEL rule failed" : rule.getMessage();
                    violations.add(new ValidationResult.Violation(path, id, msg));
                }
            } else if (result instanceof String text) {
                if (!text.isEmpty()) {
                    violations.add(new ValidationResult.Violation(path, id, text));
                }
            } else {
                violations.add(new ValidationResult.Violation(
                        path, id, "CEL rule must return bool or string"));
            }
        } catch (CelEvaluationException e) {
            violations.add(new ValidationResult.Violation(
                    path, id, "CEL evaluation error: " + e.getMessage()));
        }
    }

    private static boolean isPresent(Message message, FieldDescriptor field) {
        if (field.isRepeated()) {
            return message.getRepeatedFieldCount(field) > 0;
        }
        if (field.hasPresence()) {
            return message.hasField(field);
        }
        Object value = message.getField(field);
        return switch (field.getJavaType()) {
            case STRING -> value instanceof String s && !s.isEmpty();
            case BYTE_STRING -> value instanceof com.google.protobuf.ByteString b && !b.isEmpty();
            case MESSAGE -> message.hasField(field);
            case ENUM -> ((com.google.protobuf.Descriptors.EnumValueDescriptor) value).getNumber() != 0;
            case BOOLEAN -> (Boolean) value;
            case INT, LONG -> ((Number) value).longValue() != 0L;
            case FLOAT, DOUBLE -> ((Number) value).doubleValue() != 0.0d;
            default -> true;
        };
    }

    private static ValidationResult.Violation violation(String path, String id, String message) {
        return new ValidationResult.Violation(path, id, message);
    }
}
