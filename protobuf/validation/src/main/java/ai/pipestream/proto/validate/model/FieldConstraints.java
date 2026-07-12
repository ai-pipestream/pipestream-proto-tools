package ai.pipestream.proto.validate.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Neutral, source-agnostic constraints for a single field. A
 * {@link ai.pipestream.proto.validate.spi.ValidationRuleSource} translates its own
 * annotation dialect (Pipestream {@code validate.v1}, {@code buf.validate}, …) into
 * this model; the validator core evaluates only this model and never reads a specific
 * option dialect directly.
 */
public record FieldConstraints(
        boolean required,
        Optional<StringConstraints> string,
        Optional<IntegralConstraints> integral,
        Optional<FloatingConstraints> floating,
        List<CelConstraint> cel) {

    public FieldConstraints {
        Objects.requireNonNull(string, "string");
        Objects.requireNonNull(integral, "integral");
        Objects.requireNonNull(floating, "floating");
        cel = List.copyOf(Objects.requireNonNull(cel, "cel"));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; empty sub-constraints collapse to {@link Optional#empty()}. */
    public static final class Builder {
        private boolean required;
        private StringConstraints string;
        private IntegralConstraints integral;
        private FloatingConstraints floating;
        private final List<CelConstraint> cel = new ArrayList<>();

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder string(StringConstraints string) {
            this.string = (string == null || string.isEmpty()) ? null : string;
            return this;
        }

        public Builder integral(IntegralConstraints integral) {
            this.integral = (integral == null || integral.isEmpty()) ? null : integral;
            return this;
        }

        public Builder floating(FloatingConstraints floating) {
            this.floating = (floating == null || floating.isEmpty()) ? null : floating;
            return this;
        }

        public Builder addCel(CelConstraint constraint) {
            cel.add(Objects.requireNonNull(constraint, "constraint"));
            return this;
        }

        public FieldConstraints build() {
            return new FieldConstraints(
                    required,
                    Optional.ofNullable(string),
                    Optional.ofNullable(integral),
                    Optional.ofNullable(floating),
                    List.copyOf(cel));
        }
    }
}
