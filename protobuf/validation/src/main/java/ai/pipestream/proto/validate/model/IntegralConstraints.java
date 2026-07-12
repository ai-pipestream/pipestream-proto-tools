package ai.pipestream.proto.validate.model;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Neutral bound constraints for integral fields ({@code int32}/{@code int64} and
 * their variants). {@code ruleIdPrefix} preserves the source's category label so
 * violation ids stay stable (for example {@code int32.gt}, {@code int64.lte}).
 */
public record IntegralConstraints(
        String ruleIdPrefix,
        OptionalLong gt,
        OptionalLong gte,
        OptionalLong lt,
        OptionalLong lte) {

    public IntegralConstraints {
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
