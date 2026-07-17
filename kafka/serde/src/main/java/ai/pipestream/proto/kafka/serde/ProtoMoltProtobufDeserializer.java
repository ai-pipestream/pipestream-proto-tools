package ai.pipestream.proto.kafka.serde;

import ai.pipestream.proto.validate.ProtoValidator;
import ai.pipestream.proto.validate.ValidationResult;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Kafka protobuf deserializer that reads the Confluent wire format and resolves the message
 * type from the descriptor set the deployment packages, not from a registry lookup per record.
 *
 * <p>The frame carries a schema id and an index path. The id names a schema in whichever registry
 * wrote it, which this deserializer does not consult; the index path, however, describes the
 * message's position in its file, and that is checked against the configured type. A frame whose
 * index path points somewhere else is a topic carrying a type this consumer was not configured
 * for, and saying so beats parsing the bytes as the wrong message and returning nonsense.</p>
 *
 * <p>Validation on read is off by default. It is worth turning on for a topic whose producers do
 * not all go through this serde, which is the only way invalid data gets in.</p>
 *
 * @see ProtoMoltSerdeConfig
 */
public class ProtoMoltProtobufDeserializer implements Deserializer<Message> {

    private ProtoValidator validator;
    private Descriptor messageType;
    private List<Integer> indexPath;
    private boolean validateOnRead;
    private SchemaIds schemaIds;

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        ProtoMoltSerdeConfig config = new ProtoMoltSerdeConfig(configs);
        List<FileDescriptor> files =
                ProtoMoltProtobufSerializer.Descriptors.load(config, getClass().getClassLoader());
        messageType = SerdeDescriptors.messageType(files,
                config.getString(ProtoMoltSerdeConfig.MESSAGE_TYPE));
        indexPath = ConfluentWireFormat.indexPath(messageType);
        validateOnRead = config.getBoolean(ProtoMoltSerdeConfig.VALIDATE_ON_READ);
        validator = ProtoValidator.create();
        schemaIds = SchemaIds.create(config.getString(ProtoMoltSerdeConfig.REGISTRY_URL));
    }

    @Override
    public void close() {
        if (schemaIds != null) {
            schemaIds.close();
        }
    }

    @Override
    public Message deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        List<Integer> framedIndex = ConfluentWireFormat.messageIndex(data);
        if (!framedIndex.equals(indexPath)) {
            throw new SerializationException("A record on " + topic + " points at message index "
                    + framedIndex + ", but this deserializer is configured for "
                    + messageType.getFullName() + " at index " + indexPath
                    + "; the topic is carrying a type this consumer does not expect");
        }
        Descriptor type = resolve(ConfluentWireFormat.schemaId(data), framedIndex);
        Message message;
        try {
            message = DynamicMessage.parseFrom(type, ConfluentWireFormat.payload(data));
        } catch (InvalidProtocolBufferException e) {
            throw new SerializationException("A record on " + topic + " is not a valid "
                    + type.getFullName() + ": " + e.getMessage(), e);
        }
        if (validateOnRead) {
            ValidationResult result = validator.validate(message);
            if (!result.valid()) {
                throw new SerializationException("A record on " + topic + " violates the schema's "
                        + "declared rules: " + describe(result));
            }
        }
        return message;
    }

    /**
     * The schema the frame's id names, when a registry can say, and the packaged one otherwise.
     *
     * <p>The registry is the better answer because it describes what the writer actually wrote,
     * which is how a consumer follows a topic whose producers moved on. Falling back rather than
     * failing keeps the registry a metadata service instead of a runtime dependency of every
     * consumer: an unreachable registry should not stop a consumer whose packaged schema still
     * reads the bytes in front of it.</p>
     */
    private Descriptor resolve(int schemaId, List<Integer> framedIndex) {
        if (schemaIds == null) {
            return messageType;
        }
        Descriptor resolved = schemaIds.messageFor(schemaId, framedIndex);
        return resolved != null ? resolved : messageType;
    }

    private static String describe(ValidationResult result) {
        return result.violations().stream()
                .map(violation -> violation.path() + " " + violation.message()
                        + " (" + violation.ruleId() + ")")
                .collect(Collectors.joining("; "));
    }
}
