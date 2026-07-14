package ai.pipestream.proto.actions;

import ai.pipestream.proto.index.lucene.LuceneFieldSpecs;
import ai.pipestream.proto.index.opensearch.OpenSearchMappingGenerator;
import ai.pipestream.proto.index.solr.SolrSchemaGenerator;
import ai.pipestream.proto.index.spi.CatalogIndexingHintSource;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.IndexingPlanFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.Descriptor;

/** Renders the search-index artifact (OpenSearch/Solr/Lucene) for a protobuf message type. */
final class RenderIndexMappingsAction implements ProtoAction {

    @Override
    public String name() {
        return "render-index-mappings";
    }

    @Override
    public String description() {
        return "Renders the search-index artifact for a protobuf message type — OpenSearch index "
                + "mappings JSON, Solr managed-schema pieces, or Lucene field specs — from its "
                + "indexing hints (ai.pipestream.proto.index.hints.v1 options), inferring sensible "
                + "field kinds where no hint is declared.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ActionJson.baseInputSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("schema", ActionJson.schemaSourceSchema());
        properties.set("type", ActionJson.typeProperty(
                "Fully qualified message type to plan indexing for; required unless the schema "
                        + "already identifies a single message."));
        ObjectNode engine = properties.putObject("engine");
        engine.put("type", "string");
        engine.put("description", "Target search engine for the rendered artifact.");
        ArrayNode engines = engine.putArray("enum");
        engines.add("opensearch");
        engines.add("solr");
        engines.add("lucene");
        ActionJson.required(schema, "schema", "engine");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ObjectNode execute(ObjectNode input, ActionContext context) throws ActionException {
        SchemaResolver.ResolvedSchema schema = SchemaResolver.resolve(input, "schema", context);
        Descriptor descriptor = schema.message(Inputs.optionalString(input, "type"), "/type");
        String engine = Inputs.requireString(input, "engine");
        IndexingPlan plan = IndexingPlanFactory.defaults(new CatalogIndexingHintSource())
                .create(descriptor);
        return switch (engine) {
            case "opensearch" -> {
                ObjectNode mappings = context.objectMapper()
                        .valueToTree(new OpenSearchMappingGenerator().generate(plan));
                yield mappings;
            }
            case "solr" -> solr(plan, context);
            case "lucene" -> lucene(plan, context);
            default -> throw Inputs.invalidInput(
                    "Unknown engine '" + engine + "'; expected one of opensearch, solr, lucene",
                    "/engine");
        };
    }

    private static ObjectNode solr(IndexingPlan plan, ActionContext context) {
        SolrSchemaGenerator.SolrSchema solrSchema = new SolrSchemaGenerator().generate(plan);
        ObjectNode output = context.objectMapper().createObjectNode();
        output.set("fieldTypes", context.objectMapper().valueToTree(solrSchema.fieldTypes()));
        output.set("fields", context.objectMapper().valueToTree(solrSchema.fields()));
        output.set("copyFields", context.objectMapper().valueToTree(solrSchema.copyFields()));
        return output;
    }

    private static ObjectNode lucene(IndexingPlan plan, ActionContext context) {
        LuceneFieldSpecs specs = LuceneFieldSpecs.from(plan);
        ObjectNode output = context.objectMapper().createObjectNode();
        output.put("messageFullName", specs.messageFullName());
        ArrayNode fields = output.putArray("fields");
        for (LuceneFieldSpecs.FieldSpec spec : specs.fields()) {
            ObjectNode field = fields.addObject();
            field.put("name", spec.name());
            field.put("kind", spec.kind().name());
            field.put("stored", spec.stored());
            field.put("indexed", spec.indexed());
            field.put("sortable", spec.sortable());
            field.put("facetable", spec.facetable());
            field.put("analyzer", spec.analyzer());
            field.put("searchAnalyzer", spec.searchAnalyzer());
            field.put("vectorDims", spec.vectorDims());
            field.put("vectorSimilarity", spec.vectorSimilarity() == null
                    ? null : spec.vectorSimilarity().name());
            field.put("vectorElementType", spec.vectorElementType() == null
                    ? null : spec.vectorElementType().name());
            field.put("dateFormat", spec.dateFormat());
            ObjectNode engineParams = field.putObject("engineParams");
            spec.engineParams().forEach(engineParams::put);
        }
        return output;
    }
}
