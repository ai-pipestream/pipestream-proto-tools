package ai.pipestream.proto.validate;

import ai.pipestream.proto.cel.CelEnvironmentFactory;
import ai.pipestream.proto.cel.CelEvaluationException;
import ai.pipestream.proto.cel.CelEvaluator;
import ai.pipestream.proto.validate.model.CelConstraint;
import ai.pipestream.proto.validate.model.FieldConstraints;
import ai.pipestream.proto.validate.model.FloatingConstraints;
import ai.pipestream.proto.validate.model.IntegralConstraints;
import ai.pipestream.proto.validate.model.MessageConstraints;
import ai.pipestream.proto.validate.model.StringConstraints;
import ai.pipestream.proto.validate.spi.ValidationRuleSource;
import ai.pipestream.proto.validate.spi.ValidationRuleSources;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates protobuf messages against constraint annotations. The validator core
 * evaluates a neutral {@link FieldConstraints}/{@link MessageConstraints} model; the
 * mapping from a specific annotation dialect (Pipestream {@code validate.v1},
 * {@code buf.validate}, …) onto that model lives behind {@link ValidationRuleSource}.
 * Standard constraints run in-process; custom rules use CEL with {@code this}.
 *
 * <p>By default the built-in Pipestream reader is used plus any {@link ValidationRuleSource}
 * discovered on the classpath via {@link java.util.ServiceLoader}. Every configured source
 * is consulted per field/message and all violations are merged.
 */
public final class ProtoValidator {

    private final CelEvaluator fieldCel;
    private final CelEvaluator messageCel;
    private final List<ValidationRuleSource> sources;

    /** Uses the default rule-source chain ({@link ValidationRuleSources#defaults()}). */
    public ProtoValidator(CelEvaluator fieldCel, CelEvaluator messageCel) {
        this(fieldCel, messageCel, ValidationRuleSources.defaults());
    }

    public ProtoValidator(
            CelEvaluator fieldCel, CelEvaluator messageCel, List<ValidationRuleSource> sources) {
        this.fieldCel = Objects.requireNonNull(fieldCel, "fieldCel");
        this.messageCel = Objects.requireNonNull(messageCel, "messageCel");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    /** Default CEL environments: {@code this} is DYN for field and message rules. */
    public static ProtoValidator create() {
        return create(ValidationRuleSources.defaults());
    }

    /** As {@link #create()} but with an explicit rule-source chain. */
    public static ProtoValidator create(List<ValidationRuleSource> sources) {
        CelEvaluator field = new CelEvaluator(CelEnvironmentFactory.builder()
                .addVar("this")
                .build());
        CelEvaluator message = new CelEvaluator(CelEnvironmentFactory.builder()
                .addVar("this")
                .build());
        return new ProtoValidator(field, message, sources);
    }

    /**
     * Builds a validator whose message-level CEL knows {@code descriptor}'s type
     * (field access like {@code this.age}).
     */
    public static ProtoValidator forMessageType(Descriptor descriptor) {
        return forMessageType(descriptor, ValidationRuleSources.defaults());
    }

    /** As {@link #forMessageType(Descriptor)} but with an explicit rule-source chain. */
    public static ProtoValidator forMessageType(
            Descriptor descriptor, List<ValidationRuleSource> sources) {
        Objects.requireNonNull(descriptor, "descriptor");
        CelEvaluator field = new CelEvaluator(CelEnvironmentFactory.builder()
                .addVar("this")
                .build());
        CelEvaluator message = new CelEvaluator(CelEnvironmentFactory.builder()
                .addMessageType(descriptor)
                .addVar("this")
                .build());
        return new ProtoValidator(field, message, sources);
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

    private List<FieldConstraints> fieldConstraints(FieldDescriptor field) {
        List<FieldConstraints> collected = new ArrayList<>(sources.size());
        for (ValidationRuleSource source : sources) {
            source.fieldConstraints(field).ifPresent(collected::add);
        }
        return collected;
    }

    private void validateField(
            Message message,
            FieldDescriptor field,
            String path,
            List<ValidationResult.Violation> violations) {
        List<FieldConstraints> constraints = fieldConstraints(field);
        boolean present = isPresent(message, field);

        boolean requiredUnset = constraints.stream().anyMatch(FieldConstraints::required) && !present;
        if (requiredUnset) {
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
                    for (FieldConstraints c : constraints) {
                        runFieldCel(c, element, path + "[" + i + "]", violations);
                    }
                    i++;
                }
            }
            return;
        }

        for (FieldConstraints c : constraints) {
            applyFieldConstraints(field, c, value, path, violations);
            runFieldCel(c, value, path, violations);
        }
        if (value instanceof Message nested) {
            for (FieldDescriptor child : nested.getDescriptorForType().getFields()) {
                validateField(nested, child, path + "." + child.getName(), violations);
            }
            validateMessageRules(nested, nested.getDescriptorForType(), path, violations);
        }
    }

    private static void applyFieldConstraints(
            FieldDescriptor field,
            FieldConstraints constraints,
            Object value,
            String path,
            List<ValidationResult.Violation> violations) {
        switch (field.getJavaType()) {
            case STRING -> constraints.string()
                    .ifPresent(s -> applyString(s, (String) value, path, violations));
            case INT, LONG -> constraints.integral()
                    .ifPresent(n -> applyIntegral(n, ((Number) value).longValue(), path, violations));
            case FLOAT, DOUBLE -> constraints.floating()
                    .ifPresent(n -> applyFloating(n, ((Number) value).doubleValue(), path, violations));
            default -> {
            }
        }
    }

    private static void applyString(
            StringConstraints rules, String value, String path,
            List<ValidationResult.Violation> violations) {
        long len = value.codePointCount(0, value.length());
        if (rules.minLen().isPresent() && len < rules.minLen().getAsLong()) {
            violations.add(new ValidationResult.Violation(
                    path, "string.min_len", "length must be at least " + rules.minLen().getAsLong()));
        }
        if (rules.maxLen().isPresent() && len > rules.maxLen().getAsLong()) {
            violations.add(new ValidationResult.Violation(
                    path, "string.max_len", "length must be at most " + rules.maxLen().getAsLong()));
        }
        if (rules.pattern().isPresent()
                && !Pattern.compile(rules.pattern().get()).matcher(value).find()) {
            violations.add(new ValidationResult.Violation(
                    path, "string.pattern", "value does not match pattern"));
        }
        if (rules.email() && !looksLikeEmail(value)) {
            violations.add(new ValidationResult.Violation(
                    path, "string.email", "value must look like an email address"));
        }
    }

    private static boolean looksLikeEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 1 && value.indexOf('@', at + 1) < 0;
    }

    private static void applyIntegral(
            IntegralConstraints rules, long value, String path,
            List<ValidationResult.Violation> violations) {
        String prefix = rules.ruleIdPrefix();
        if (rules.gt().isPresent() && !(value > rules.gt().getAsLong())) {
            violations.add(violation(path, prefix + ".gt", "must be > " + rules.gt().getAsLong()));
        }
        if (rules.gte().isPresent() && !(value >= rules.gte().getAsLong())) {
            violations.add(violation(path, prefix + ".gte", "must be >= " + rules.gte().getAsLong()));
        }
        if (rules.lt().isPresent() && !(value < rules.lt().getAsLong())) {
            violations.add(violation(path, prefix + ".lt", "must be < " + rules.lt().getAsLong()));
        }
        if (rules.lte().isPresent() && !(value <= rules.lte().getAsLong())) {
            violations.add(violation(path, prefix + ".lte", "must be <= " + rules.lte().getAsLong()));
        }
    }

    private static void applyFloating(
            FloatingConstraints rules, double value, String path,
            List<ValidationResult.Violation> violations) {
        String prefix = rules.ruleIdPrefix();
        if (rules.gt().isPresent() && !(value > rules.gt().getAsDouble())) {
            violations.add(violation(path, prefix + ".gt", "must be > " + rules.gt().getAsDouble()));
        }
        if (rules.gte().isPresent() && !(value >= rules.gte().getAsDouble())) {
            violations.add(violation(path, prefix + ".gte", "must be >= " + rules.gte().getAsDouble()));
        }
        if (rules.lt().isPresent() && !(value < rules.lt().getAsDouble())) {
            violations.add(violation(path, prefix + ".lt", "must be < " + rules.lt().getAsDouble()));
        }
        if (rules.lte().isPresent() && !(value <= rules.lte().getAsDouble())) {
            violations.add(violation(path, prefix + ".lte", "must be <= " + rules.lte().getAsDouble()));
        }
    }

    private void runFieldCel(
            FieldConstraints constraints, Object value, String path,
            List<ValidationResult.Violation> violations) {
        for (CelConstraint rule : constraints.cel()) {
            evalCel(fieldCel, rule, value, path, violations);
        }
    }

    private void validateMessageRules(
            Message message,
            Descriptor descriptor,
            String path,
            List<ValidationResult.Violation> violations) {
        for (ValidationRuleSource source : sources) {
            MessageConstraints constraints = source.messageConstraints(descriptor).orElse(null);
            if (constraints == null || constraints.isEmpty()) {
                continue;
            }
            String msgPath = path.isEmpty() ? descriptor.getName() : path;
            for (CelConstraint rule : constraints.cel()) {
                evalCel(messageCel, rule, message, msgPath, violations);
            }
        }
    }

    private static void evalCel(
            CelEvaluator evaluator,
            CelConstraint rule,
            Object thisValue,
            String path,
            List<ValidationResult.Violation> violations) {
        if (rule.expression().isBlank()) {
            return;
        }
        String id = rule.id().isBlank() ? "cel" : rule.id();
        try {
            Object result = evaluator.evaluateValue(rule.expression(), Map.of("this", thisValue));
            if (result instanceof Boolean ok) {
                if (!ok) {
                    String msg = rule.message().isBlank() ? "CEL rule failed" : rule.message();
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
