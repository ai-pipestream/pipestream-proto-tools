package ai.pipestream.proto.validate.model;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Neutral bound constraints for floating-point fields ({@code float}/{@code double}).
 * {@code ruleIdPrefix} preserves the source's category label so violation ids stay
 * stable (for example {@code float.gt}, {@code double.lte}).
 */
public record FloatingConstraints(
        String ruleIdPrefix,
        OptionalDouble gt,
        OptionalDouble gte,
        OptionalDouble lt,
        OptionalDouble lte) {

    public FloatingConstraints {
        Objects.requireNonNull(ruleIdPrefix, "ruleIdPrefix");
        Objects.requireNonNull(gt, "gt");
        Objects.requireNonNull(gte, "gte");
        Objects.requireNonNull(lt, "lt");
        Objects.requireNonNull(lte, "lte");
    }

    public boolean isEmpty() {
        return gt.isEmpty() && gte.isEmpty() && lt.isEmpty() && lte.isEmpty();
    }
}
