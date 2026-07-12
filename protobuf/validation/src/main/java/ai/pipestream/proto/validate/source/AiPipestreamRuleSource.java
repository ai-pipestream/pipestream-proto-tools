package ai.pipestream.proto.validate.source;

import ai.pipestream.proto.validate.CelRule;
import ai.pipestream.proto.validate.DoubleRules;
import ai.pipestream.proto.validate.FieldRules;
import ai.pipestream.proto.validate.FloatRules;
import ai.pipestream.proto.validate.Int32Rules;
import ai.pipestream.proto.validate.Int64Rules;
import ai.pipestream.proto.validate.MessageRules;
import ai.pipestream.proto.validate.StringRules;
import ai.pipestream.proto.validate.ValidateProto;
import ai.pipestream.proto.validate.model.CelConstraint;
import ai.pipestream.proto.validate.model.FieldConstraints;
import ai.pipestream.proto.validate.model.FloatingConstraints;
import ai.pipestream.proto.validate.model.IntegralConstraints;
import ai.pipestream.proto.validate.model.MessageConstraints;
import ai.pipestream.proto.validate.model.StringConstraints;
import ai.pipestream.proto.validate.spi.ValidationRuleSource;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Built-in {@link ValidationRuleSource} reading the Pipestream {@code validate.v1}
 * options — {@code (ai.pipestream.proto.validate.v1.field)} and {@code (…​.message)} —
 * off protobuf descriptors and translating them into the neutral rule model.
 */
public final class AiPipestreamRuleSource implements ValidationRuleSource {

    @Override
    public Optional<FieldConstraints> fieldConstraints(FieldDescriptor field) {
        var options = field.getOptions();
        if (!options.hasExtension(ValidateProto.field)) {
            return Optional.empty();
        }
        FieldRules rules = options.getExtension(ValidateProto.field);
        FieldConstraints.Builder builder = FieldConstraints.builder().required(rules.getRequired());
        if (rules.hasString()) {
            builder.string(toStringConstraints(rules.getString()));
        }
        // A field is either int32 or int64; prefer the more specific rule set when both exist.
        if (rules.hasInt64()) {
            builder.integral(toInt64(rules.getInt64()));
        } else if (rules.hasInt32()) {
            builder.integral(toInt32(rules.getInt32()));
        }
        if (rules.hasDouble()) {
            builder.floating(toDouble(rules.getDouble()));
        } else if (rules.hasFloat()) {
            builder.floating(toFloat(rules.getFloat()));
        }
        for (CelRule cel : rules.getCelList()) {
            builder.addCel(toCel(cel));
        }
        return Optional.of(builder.build());
    }

    @Override
    public Optional<MessageConstraints> messageConstraints(Descriptor message) {
        var options = message.getOptions();
        if (!options.hasExtension(ValidateProto.message)) {
            return Optional.empty();
        }
        MessageRules rules = options.getExtension(ValidateProto.message);
        List<CelConstraint> cel = new ArrayList<>(rules.getCelList().size());
        for (CelRule rule : rules.getCelList()) {
            cel.add(toCel(rule));
        }
        return Optional.of(new MessageConstraints(cel));
    }

    private static CelConstraint toCel(CelRule rule) {
        return new CelConstraint(rule.getId(), rule.getExpression(), rule.getMessage());
    }

    private static StringConstraints toStringConstraints(StringRules rules) {
        return new StringConstraints(
                rules.hasMinLen() ? OptionalLong.of(rules.getMinLen()) : OptionalLong.empty(),
                rules.hasMaxLen() ? OptionalLong.of(rules.getMaxLen()) : OptionalLong.empty(),
                rules.hasPattern() && !rules.getPattern().isEmpty()
                        ? Optional.of(rules.getPattern()) : Optional.empty(),
                rules.getEmail());
    }

    private static IntegralConstraints toInt32(Int32Rules r) {
        return new IntegralConstraints(
                "int32",
                r.hasGt() ? OptionalLong.of(r.getGt()) : OptionalLong.empty(),
                r.hasGte() ? OptionalLong.of(r.getGte()) : OptionalLong.empty(),
                r.hasLt() ? OptionalLong.of(r.getLt()) : OptionalLong.empty(),
                r.hasLte() ? OptionalLong.of(r.getLte()) : OptionalLong.empty());
    }

    private static IntegralConstraints toInt64(Int64Rules r) {
        return new IntegralConstraints(
                "int64",
                r.hasGt() ? OptionalLong.of(r.getGt()) : OptionalLong.empty(),
                r.hasGte() ? OptionalLong.of(r.getGte()) : OptionalLong.empty(),
                r.hasLt() ? OptionalLong.of(r.getLt()) : OptionalLong.empty(),
                r.hasLte() ? OptionalLong.of(r.getLte()) : OptionalLong.empty());
    }

    private static FloatingConstraints toFloat(FloatRules r) {
        return new FloatingConstraints(
                "float",
                r.hasGt() ? OptionalDouble.of(r.getGt()) : OptionalDouble.empty(),
                r.hasGte() ? OptionalDouble.of(r.getGte()) : OptionalDouble.empty(),
                r.hasLt() ? OptionalDouble.of(r.getLt()) : OptionalDouble.empty(),
                r.hasLte() ? OptionalDouble.of(r.getLte()) : OptionalDouble.empty());
    }

    private static FloatingConstraints toDouble(DoubleRules r) {
        return new FloatingConstraints(
                "double",
                r.hasGt() ? OptionalDouble.of(r.getGt()) : OptionalDouble.empty(),
                r.hasGte() ? OptionalDouble.of(r.getGte()) : OptionalDouble.empty(),
                r.hasLt() ? OptionalDouble.of(r.getLt()) : OptionalDouble.empty(),
                r.hasLte() ? OptionalDouble.of(r.getLte()) : OptionalDouble.empty());
    }
}
