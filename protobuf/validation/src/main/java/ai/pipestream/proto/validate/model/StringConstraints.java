package ai.pipestream.proto.validate.model;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Neutral constraints for string fields. Rule ids emitted for violations are the
 * fixed {@code string.min_len}, {@code string.max_len}, {@code string.pattern},
 * and {@code string.email}.
 */
public record StringConstraints(
        OptionalLong minLen,
        OptionalLong maxLen,
        Optional<String> pattern,
        boolean email) {

    public StringConstraints {
        Objects.requireNonNull(minLen, "minLen");
        Objects.requireNonNull(maxLen, "maxLen");
        Objects.requireNonNull(pattern, "pattern");
    }

    /** True when no string constraint is actually set (safe to skip). */
    public boolean isEmpty() {
        return minLen.isEmpty() && maxLen.isEmpty() && pattern.isEmpty() && !email;
    }
}
