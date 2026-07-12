package ai.pipestream.proto.schema.confluent;

import ai.pipestream.proto.descriptors.DescriptorLoader;
import ai.pipestream.proto.descriptors.GoogleDescriptorLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;
import com.squareup.wire.schema.internal.SchemaEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads protobuf descriptors from a Confluent Schema Registry (or any compatible endpoint,
 * such as Apicurio Registry's ccompat facade) by speaking the subjects REST API.
 *
 * <p>Unlike {@link ConfluentDescriptorSource}, which consumes a pre-compiled binary
 * {@code FileDescriptorSet} served over plain HTTP, this loader consumes what a real
 * Schema Registry actually serves: {@code .proto} schema <em>text</em> (schemaType
 * {@code PROTOBUF}) with optional schema references. Schema text is parsed and linked with
 * Square Wire's schema library, encoded to {@code FileDescriptorProto}s and built into
 * {@link FileDescriptor}s.</p>
 *
 * <h2>Protocol</h2>
 * <ul>
 *   <li>{@link #loadDescriptors()} — {@code GET /subjects}, then
 *       {@code GET /subjects/{subject}/versions/latest} for each subject. Subjects whose
 *       {@code schemaType} is not {@code PROTOBUF} are skipped with a debug log. Subjects that
 *       fail to resolve (dangling references, reference cycles, unparseable schemas, per-subject
 *       HTTP errors) are skipped with a warning, so one bad subject never poisons the rest.</li>
 *   <li>References ({@code [{name, subject, version}]}) are fetched recursively via
 *       {@code GET /subjects/{subject}/versions/{version}} and provided to the compiler under
 *       their reference {@code name}, which by Schema Registry convention is the import path
 *       used by the referencing schema.</li>
 * </ul>
 *
 * <h2>Lookup strategy for {@link #loadDescriptor(String)}</h2>
 * <p>The registry has no type-name index, so lookup scans the loaded subjects: the argument is
 * matched first as a proto file name (e.g. {@code my_types.proto}, where a subject's own schema
 * is named after the subject, sanitized and suffixed with {@code .proto}), then as a
 * fully-qualified message name, then as a simple message name. Returns {@code null} when nothing
 * matches.</p>
 *
 * <p>Well-known {@code google/protobuf/*.proto} imports resolve without being registered as
 * references: Wire bundles {@code any, descriptor, duration, empty, struct, timestamp, wrappers}
 * and this loader supplies {@code field_mask}; the built descriptors for those files come from
 * protobuf-java's runtime. Like {@link ConfluentDescriptorSource}, the loader talks anonymous
 * HTTP (no auth) via {@link HttpClient}.</p>
 */
public final class ConfluentSchemaRegistryLoader implements DescriptorLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfluentSchemaRegistryLoader.class);

    private static final String SCHEMA_REGISTRY_ACCEPT =
            "application/vnd.schemaregistry.v1+json, application/json";

    /** {@code field_mask.proto} is a well-known import that Wire does not bundle. */
    private static final String FIELD_MASK_PATH = "google/protobuf/field_mask.proto";
    private static final String FIELD_MASK_PROTO = """
            syntax = "proto3";
            package google.protobuf;
            message FieldMask {
              repeated string paths = 1;
            }
            """;

    private final URI baseUrl;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Creates a loader for the given registry base URL, e.g. {@code http://localhost:8081} or
     * {@code http://localhost:8080/apis/ccompat/v7} (no trailing slash required).
     *
     * @param baseUrl base URL of the Confluent-compatible registry
     */
    public ConfluentSchemaRegistryLoader(URI baseUrl) {
        String url = Objects.requireNonNull(baseUrl, "baseUrl").toString();
        this.baseUrl = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public List<FileDescriptor> loadDescriptors() throws DescriptorLoadException {
        List<String> subjects = listSubjects();
        Map<String, JsonNode> versionCache = new HashMap<>();
        List<FileDescriptor> descriptors = new ArrayList<>(subjects.size());
        for (String subject : subjects) {
            try {
                FileDescriptor descriptor = loadSubject(subject, versionCache);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DescriptorLoadException("Interrupted while loading subject " + subject, e);
            } catch (Exception e) {
                LOG.warn("Skipping subject {}: {}", subject, e.getMessage());
                LOG.debug("Failed to load subject {}", subject, e);
            }
        }
        return descriptors;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Matches by proto file name first, then by fully-qualified message name, then by simple
     * message name (see the class javadoc for the full strategy).</p>
     */
    @Override
    public FileDescriptor loadDescriptor(String fileName) throws DescriptorLoadException {
        List<FileDescriptor> descriptors = loadDescriptors();
        for (FileDescriptor descriptor : descriptors) {
            if (descriptor.getName().equals(fileName)) {
                return descriptor;
            }
        }
        for (FileDescriptor descriptor : descriptors) {
            if (containsMessage(descriptor.getMessageTypes(), fileName)) {
                return descriptor;
            }
        }
        return null;
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/subjects"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", SCHEMA_REGISTRY_ACCEPT)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public String getLoaderType() {
        return "Confluent Schema Registry subjects API";
    }

    // ---------------------------------------------------------------- registry protocol

    private List<String> listSubjects() throws DescriptorLoadException {
        JsonNode node;
        try {
            node = getJson("/subjects");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DescriptorLoadException("Interrupted while listing subjects", e);
        } catch (IOException e) {
            throw new DescriptorLoadException("Failed to list registry subjects", e);
        }
        List<String> subjects = new ArrayList<>();
        for (JsonNode subject : node) {
            subjects.add(subject.asText());
        }
        return subjects;
    }

    /**
     * Loads one subject's latest schema plus its transitive references, compiles the schema text
     * and returns the subject's own {@link FileDescriptor}. Returns {@code null} for
     * non-PROTOBUF subjects.
     */
    private FileDescriptor loadSubject(String subject, Map<String, JsonNode> versionCache)
            throws Exception {
        JsonNode latest = getJson("/subjects/" + encode(subject) + "/versions/latest");
        String schemaType = latest.path("schemaType").asText("AVRO");
        if (!"PROTOBUF".equals(schemaType)) {
            LOG.debug("Skipping subject {} with schemaType {}", subject, schemaType);
            return null;
        }

        // import path -> schema text, gathered depth-first over the reference graph
        Map<String, String> files = new LinkedHashMap<>();
        Deque<String> resolving = new ArrayDeque<>();
        resolving.push(subject + ":" + latest.path("version").asInt());
        collectReferences(latest.path("references"), files, resolving, versionCache);

        String rootPath = rootFileName(subject, files);
        files.put(rootPath, latest.path("schema").asText());
        files.putIfAbsent(FIELD_MASK_PATH, FIELD_MASK_PROTO);
        return compile(rootPath, files);
    }

    /**
     * Recursively fetches schema references, keyed by their reference {@code name} (the import
     * path). Cycles across {@code subject:version} pairs and dangling references abort the
     * enclosing subject (caught and skipped by {@link #loadDescriptors()}).
     */
    private void collectReferences(JsonNode references, Map<String, String> files,
                                   Deque<String> resolving, Map<String, JsonNode> versionCache)
            throws Exception {
        for (JsonNode reference : references) {
            String name = reference.path("name").asText();
            String subject = reference.path("subject").asText();
            int version = reference.path("version").asInt();
            if (files.containsKey(name)) {
                continue; // already resolved under this import path
            }
            String key = subject + ":" + version;
            if (resolving.contains(key)) {
                throw new DescriptorLoadException("Reference cycle detected at " + key
                        + " (resolution chain: " + resolving + ")");
            }
            JsonNode schema = versionCache.get(key);
            if (schema == null) {
                try {
                    schema = getJson("/subjects/" + encode(subject) + "/versions/" + version);
                } catch (DescriptorLoadException e) {
                    throw new DescriptorLoadException(
                            "Dangling reference " + name + " -> " + key + ": " + e.getMessage());
                }
                versionCache.put(key, schema);
            }
            resolving.push(key);
            try {
                collectReferences(schema.path("references"), files, resolving, versionCache);
            } finally {
                resolving.pop();
            }
            files.put(name, schema.path("schema").asText());
        }
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException, DescriptorLoadException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", SCHEMA_REGISTRY_ACCEPT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new DescriptorLoadException(
                    "Registry returned HTTP " + response.statusCode() + " for " + path);
        }
        return json.readTree(response.body());
    }

    private static String encode(String subject) {
        return URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---------------------------------------------------------------- schema compilation

    /**
     * The registry does not assign a file name to a subject's own schema, so one is synthesized
     * from the subject name (sanitized, {@code .proto} suffix), dodging collisions with
     * reference names.
     */
    private static String rootFileName(String subject, Map<String, String> files) {
        String name = subject.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.endsWith(".proto")) {
            name += ".proto";
        }
        while (files.containsKey(name)) {
            name = "_" + name;
        }
        return name;
    }

    /**
     * Writes the schema texts to a scratch directory, links them with Wire (which supplies the
     * bundled {@code google/protobuf} imports), encodes every file reachable from the root into
     * a {@code FileDescriptorSet} and builds runtime descriptors from it.
     */
    private FileDescriptor compile(String rootPath, Map<String, String> files) throws Exception {
        Path dir = Files.createTempDirectory("pipestream-confluent-sr");
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = dir.resolve(file.getKey()).normalize();
                if (!target.startsWith(dir)) {
                    throw new DescriptorLoadException("Unsafe schema path: " + file.getKey());
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
            }

            SchemaLoader loader = new SchemaLoader(FileSystems.getDefault());
            loader.setLoadExhaustively(true);
            loader.initRoots(List.of(Location.get(dir.toString())), List.of());
            Schema schema = loader.loadSchema();

            Map<String, FileDescriptorProto> protos = new LinkedHashMap<>();
            encodeWithDependencies(rootPath, schema, new SchemaEncoder(schema), protos);
            FileDescriptorSet set = FileDescriptorSet.newBuilder().addAllFile(protos.values()).build();

            return GoogleDescriptorLoader.fromDescriptorSet(set).stream()
                    .filter(fd -> fd.getName().equals(rootPath))
                    .findFirst()
                    .orElseThrow(() -> new DescriptorLoadException(
                            "Compiled schema set does not contain " + rootPath));
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Encodes {@code path} and, transitively, every import Wire has a linked file for. Imports
     * Wire cannot supply (e.g. well-known types resolved from protobuf-java's runtime) are left
     * to {@link GoogleDescriptorLoader}'s well-known-type fallback.
     */
    private void encodeWithDependencies(String path, Schema schema, SchemaEncoder encoder,
                                        Map<String, FileDescriptorProto> out) throws IOException {
        if (out.containsKey(path)) {
            return;
        }
        ProtoFile protoFile = schema.protoFile(path);
        if (protoFile == null) {
            return;
        }
        FileDescriptorProto proto = FileDescriptorProto.parseFrom(encoder.encode(protoFile).toByteArray());
        out.put(path, proto);
        for (String dependency : proto.getDependencyList()) {
            encodeWithDependencies(dependency, schema, encoder, out);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.debug("Failed to delete {}", path, e);
                }
            });
        } catch (IOException e) {
            LOG.debug("Failed to clean up {}", dir, e);
        }
    }

    private static boolean containsMessage(List<Descriptor> messages, String name) {
        for (Descriptor message : messages) {
            if (message.getFullName().equals(name) || message.getName().equals(name)
                    || containsMessage(message.getNestedTypes(), name)) {
                return true;
            }
        }
        return false;
    }
}
