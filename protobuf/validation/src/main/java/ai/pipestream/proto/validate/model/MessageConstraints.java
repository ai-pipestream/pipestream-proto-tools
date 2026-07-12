package ai.pipestream.proto.validate.model;

import java.util.List;
import java.util.Objects;

/** Neutral, source-agnostic message-level constraints (currently CEL predicates). */
public record MessageConstraints(List<CelConstraint> cel) {

    public MessageConstraints {
        cel = List.copyOf(Objects.requireNonNull(cel, "cel"));
    }

    public boolean isEmpty() {
        return cel.isEmpty();
    }
}
