package ai.pipestream.proto.json;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import java.util.Collection;
import java.util.Objects;

/**
 * Framework-agnostic protobuf ↔ JSON transcoder.
 *
 * <p>Inspired by Micronaut's {@code ProtobufJsonTranscoder}, extended for
 * {@link DynamicMessage} via a {@link DescriptorRegistry}-backed type registry
 * (Apicurio / Confluent / classpath descriptors).
 */
public final class ProtobufJsonTranscoder {

    private final JsonFormat.Printer printer;
    private final JsonFormat.Parser parser;
    private final DescriptorRegistry descriptorRegistry;

    public ProtobufJsonTranscoder() {
        this(null);
    }

    public ProtobufJsonTranscoder(DescriptorRegistry descriptorRegistry) {
        this.descriptorRegistry = descriptorRegistry;
        JsonFormat.TypeRegistry typeRegistry = buildTypeRegistry(descriptorRegistry);
        this.printer = JsonFormat.printer()
                .usingTypeRegistry(typeRegistry)
                .alwaysPrintFieldsWithNoPresence()
                .sortingMapKeys();
        this.parser = JsonFormat.parser()
                .usingTypeRegistry(typeRegistry)
                .ignoringUnknownFields();
    }

    public String toJson(Message message) {
        Objects.requireNonNull(message, "message");
        try {
            return printer.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new ProtobufJsonException("Failed to serialize protobuf message to JSON", e);
        }
    }

    /**
     * Parses JSON into a generated message type that exposes {@code newBuilder()}.
     */
    @SuppressWarnings("unchecked")
    public <T extends Message> T fromJson(String json, Class<T> messageType) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(messageType, "messageType");
        try {
            Message.Builder builder = (Message.Builder) messageType.getMethod("newBuilder").invoke(null);
            parser.merge(json, builder);
            return (T) builder.build();
        } catch (InvalidProtocolBufferException e) {
            throw new MalformedProtobufJsonException(
                    "Failed to deserialize JSON to " + messageType.getSimpleName(), json, e);
        } catch (ReflectiveOperationException e) {
            throw new ProtobufJsonException(
                    "Type " + messageType.getName() + " is not a protobuf Message with newBuilder()", e);
        }
    }

    /**
     * Parses JSON into a {@link DynamicMessage} for the given descriptor full name,
     * resolving the descriptor through the configured {@link DescriptorRegistry}.
     */
    public DynamicMessage fromJsonDynamic(String json, String messageFullName) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(messageFullName, "messageFullName");
        if (descriptorRegistry == null) {
            throw new ProtobufJsonException(
                    "Dynamic JSON parsing requires a DescriptorRegistry (Apicurio/Confluent/classpath)");
        }
        Descriptor descriptor = descriptorRegistry.findDescriptor(messageFullName);
        if (descriptor == null) {
            throw new ProtobufJsonException("Unknown message type: " + messageFullName);
        }
        return fromJsonDynamic(json, descriptor);
    }

    public DynamicMessage fromJsonDynamic(String json, Descriptor descriptor) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(descriptor, "descriptor");
        try {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
            parser.merge(json, builder);
            return builder.build();
        } catch (InvalidProtocolBufferException e) {
            throw new MalformedProtobufJsonException(
                    "Failed to deserialize JSON to " + descriptor.getFullName(), json, e);
        }
    }

    public DescriptorRegistry getDescriptorRegistry() {
        return descriptorRegistry;
    }

    private static JsonFormat.TypeRegistry buildTypeRegistry(DescriptorRegistry registry) {
        JsonFormat.TypeRegistry.Builder builder = JsonFormat.TypeRegistry.newBuilder();
        if (registry != null) {
            Collection<Descriptor> descriptors = registry.registeredDescriptors();
            if (!descriptors.isEmpty()) {
                builder.add(descriptors);
            }
        }
        return builder.build();
    }
}
