package ai.pipestream.proto.cel;

import com.google.protobuf.Descriptors.Descriptor;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelFactory;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds CEL environments for protobuf descriptors and application bindings. */
public final class CelEnvironmentFactory {
    private final List<Descriptor> messageTypes = new ArrayList<>();
    private final Map<String, CelType> variables = new LinkedHashMap<>();

    public CelEnvironmentFactory() {
    }

    public static CelEnvironmentFactory builder() {
        return new CelEnvironmentFactory();
    }

    public CelEnvironmentFactory addMessageType(Descriptor descriptor) {
        messageTypes.add(Objects.requireNonNull(descriptor, "descriptor"));
        return this;
    }

    public CelEnvironmentFactory addVar(String name) {
        return addVar(name, SimpleType.DYN);
    }

    public CelEnvironmentFactory addVar(String name, CelType type) {
        variables.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"));
        return this;
    }

    /** Returns a builder for callers that need additional typed CEL declarations. */
    public CelBuilder advisoryBuilder() {
        CelBuilder builder = CelFactory.standardCelBuilder();
        builder.addMessageTypes(messageTypes);
        variables.forEach(builder::addVar);
        return builder;
    }

    public Cel build() {
        return advisoryBuilder().build();
    }
}
