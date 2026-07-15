package ai.pipestream.proto.actions;

import ai.pipestream.proto.json.MalformedProtobufJsonException;
import ai.pipestream.proto.meta.SensitivityMasker;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Masks a message's fields by their schema-declared sensitivity classes — the masking
 * policy travels inside the descriptor, so every surface enforces exactly what the schema
 * authors declared.
 */
final class MaskMessageAction implements ProtoAction {

    @Override
    public String name() {
        return "mask-message";
    }

    @Override
    public String description() {
        return "Masks fields whose declared sensitivity class "
                + "(ai.pipestream.proto.meta.v1.field.sensitivity) is in 'classes' — e.g. "
                + "pii, secret. Strategy 'remove' clears them; 'redact' turns strings into "
                + "*** (visibly masked) and clears everything else. Recurses through nested "
                + "and repeated messages. Returns the masked message and which field paths "
                + "were touched.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ActionJson.baseInputSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("schema", ActionJson.schemaSourceSchema());
        properties.set("type", ActionJson.typeProperty(
                "Fully qualified message type; required unless the schema identifies one."));
        properties.putObject("message")
                .put("type", "object")
                .put("description", "The message to mask, as canonical proto3 JSON.");
        ObjectNode classes = properties.putObject("classes");
        classes.put("type", "array");
        classes.put("description", "Sensitivity classes to mask, e.g. [\"pii\"].");
        classes.putObject("items").put("type", "string");
        properties.putObject("strategy")
                .put("type", "string")
                .put("description", "'remove' (default) or 'redact'.");
        ActionJson.required(schema, "schema", "message", "classes");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ObjectNode execute(ObjectNode input, ActionContext context) throws ActionException {
        SchemaResolver.ResolvedSchema schema = SchemaResolver.resolve(input, "schema", context);
        Descriptor descriptor = schema.message(Inputs.optionalString(input, "type"), "/type");
        ObjectNode messageNode = Inputs.requireObject(input, "message");
        ArrayNode classesNode = Inputs.optionalArray(input, "classes");
        if (classesNode == null || classesNode.isEmpty()) {
            throw Inputs.invalidInput("'classes' must be a non-empty array", "/classes");
        }
        Set<String> classes = new LinkedHashSet<>(
                Inputs.stringElements(classesNode, "/classes"));
        String strategyName = Inputs.optionalString(input, "strategy");
        SensitivityMasker.Strategy strategy;
        try {
            strategy = strategyName == null
                    ? SensitivityMasker.Strategy.REMOVE
                    : SensitivityMasker.Strategy.of(strategyName);
        } catch (IllegalArgumentException e) {
            throw Inputs.invalidInput("'strategy' must be 'remove' or 'redact'; got '"
                    + strategyName + "'", "/strategy");
        }
        DynamicMessage message;
        try {
            message = context.transcoder().fromJsonDynamic(messageNode.toString(), descriptor);
        } catch (MalformedProtobufJsonException e) {
            throw Inputs.invalidInput("Message is not valid proto3 JSON for "
                    + descriptor.getFullName(), "/message");
        }
        SensitivityMasker.MaskResult result =
                SensitivityMasker.mask(message, classes, strategy);
        ObjectNode output = context.objectMapper().createObjectNode();
        output.set("message", ActionJson.messageToJson(result.message(), context));
        ArrayNode masked = output.putArray("maskedFields");
        for (String path : result.maskedPaths()) {
            masked.add(path);
        }
        return output;
    }
}
