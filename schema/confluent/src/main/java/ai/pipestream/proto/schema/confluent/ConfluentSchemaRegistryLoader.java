package ai.pipestream.proto.schema.confluent;

import ai.pipestream.proto.descriptors.DescriptorLoader;
import ai.pipestream.proto.sources.CompiledProtos;
import ai.pipestream.proto.sources.ProtoSourceCompiler;
import ai.pipestream.proto.sources.ProtoSourceSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 *       {@code schemaType} is not {@code PROTOBUF} are skipped with a debug log. A subject whose
 *       own schema is broken (dangling references, reference cycles, unparseable schema text,
 *       per-subject HTTP 404) is skipped with a warning, so one bad subject never poisons the
 *       rest; a WARN summary and {@link #lastSkippedSubjectCount()} expose that skips happened.
 *       Failures that indicate an unhealthy registry rather than a broken schema (HTTP 401/403,
 *       HTTP 5xx, connection/timeout failures) abort the whole load with a
 *       {@link DescriptorLoadException}.</li>
 *   <li>References ({@code [{name, subject, version}]}) are fetched recursively via
 *       {@code GET /subjects/{subject}/versions/{version}} and provided to the compiler under
 *       their reference {@code name}, which by Schema Registry convention is the import path
 *       used by the referencing schema.</li>
 * </ul>
 *
 * <h2>Lookup strategy for {@link #loadDescriptor(String)}</h2>
 * <p>The registry has no type-name index, so lookup scans the loaded subjects in three passes:
 * the argument is matched first as a proto file name (e.g. {@code my_types.proto}, where a
 * subject's own schema is named after the subject, sanitized and suffixed with {@code .proto}),
 * then as a fully-qualified message name across all descriptors, then as a simple message name.
 * A fully-qualified match therefore always beats a simple-name match, regardless of subject
 * order. Returns {@code null} when nothing matches. To avoid re-fetching the whole registry on
 * every lookup, the compiled registry is cached for a short period
 * ({@link #LOOKUP_CACHE_TTL}); {@link #clearCache()} drops the cache immediately.</p>
 *
 * <p>Compilation is delegated to {@link ProtoSourceCompiler}. Well-known
 * {@code google/protobuf/*.proto} imports resolve without being registered as references:
 * Wire bundles {@code any, descriptor, duration, empty, struct, timestamp, wrappers}
 * and the compiler supplies {@code field_mask}; the built descriptors for those files come from
 * protobuf-java's runtime. Like {@link ConfluentDescriptorSource}, the loader talks anonymous
 * HTTP (no auth) via {@link HttpClient}. HTTP timeouts are configurable via
 * {@link #ConfluentSchemaRegistryLoader(URI, Duration, Duration)} (defaults: 10s connect,
 * 30s per request), so an unresponsive registry cannot hang startup forever. The loader owns
 * its {@link HttpClient}; call {@link #close()} when done with it.</p>
 */
public final class ConfluentSchemaRegistryLoader implements DescriptorLoader, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConfluentSchemaRegistryLoader.class);

    private static final String SCHEMA_REGISTRY_ACCEPT =
            "application/vnd.schemaregistry.v1+json, application/json";

    /** Default connect timeout for the HTTP client. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Default per-request timeout. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** How long {@link #loadDescriptor(String)} reuses a previously compiled registry. */
    public static final Duration LOOKUP_CACHE_TTL = Duration.ofSeconds(30);

    private final URI baseUrl;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final ObjectMapper json = new ObjectMapper();
    private final ProtoSourceCompiler compiler = new ProtoSourceCompiler();

    private volatile CachedDescriptors lookupCache;
    private volatile int lastSkippedSubjects;

    private record CachedDescriptors(List<FileDescriptor> descriptors, long expiresAtNanos) {
    }

    /** Registry response with a non-2xx status; carries the status for failure classification. */
    static final class RegistryHttpException extends DescriptorLoadException {
        private final int statusCode;

        RegistryHttpException(int statusCode, String path) {
            super("Registry returned HTTP " + statusCode + " for " + path);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    /**
     * Creates a loader for the given registry base URL, e.g. {@code http://localhost:8081} or
     * {@code http://localhost:8080/apis/ccompat/v7} (no trailing slash required), with default
     * timeouts ({@link #DEFAULT_CONNECT_TIMEOUT} / {@link #DEFAULT_REQUEST_TIMEOUT}).
     *
     * @param baseUrl base URL of the Confluent-compatible registry
     */
    public ConfluentSchemaRegistryLoader(URI baseUrl) {
        this(baseUrl, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Creates a loader with explicit HTTP timeouts.
     *
     * @param baseUrl base URL of the Confluent-compatible registry
     * @param connectTimeout TCP connect timeout for the underlying {@link HttpClient}
     * @param requestTimeout per-request timeout applied to every registry call
     */
    public ConfluentSchemaRegistryLoader(URI baseUrl, Duration connectTimeout, Duration requestTimeout) {
        String url = Objects.requireNonNull(baseUrl, "baseUrl").toString();
        this.baseUrl = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
    }

    @Override
    public List<FileDescriptor> loadDescriptors() throws DescriptorLoadException {
        List<String> subjects = listSubjects();
        Map<String, JsonNode> versionCache = new HashMap<>();
        List<FileDescriptor> descriptors = new ArrayList<>(subjects.size());
        int skipped = 0;
        for (String subject : subjects) {
            try {
                FileDescriptor descriptor = loadSubject(subject, versionCache);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DescriptorLoadException("Interrupted while loading subject " + subject, e);
            } catch (RegistryHttpException e) {
                if (abortsLoad(e.statusCode())) {
                    throw new DescriptorLoadException("Registry failure (HTTP " + e.statusCode()
                            + ") while loading subject " + subject + "; aborting load", e);
                }
                skipped++;
                LOG.warn("Skipping subject {}: {}", subject, e.getMessage());
            } catch (IOException e) {
                // Connection refused / timed out: the registry is unhealthy, not the schema.
                throw new DescriptorLoadException(
                        "Registry I/O failure while loading subject " + subject + "; aborting load", e);
            } catch (Exception e) {
                skipped++;
                LOG.warn("Skipping subject {}: {}", subject, e.getMessage());
                LOG.debug("Failed to load subject {}", subject, e);
            }
        }
        lastSkippedSubjects = skipped;
        if (skipped > 0) {
            LOG.warn("Skipped {} of {} subjects while loading descriptors from {}",
                    skipped, subjects.size(), baseUrl);
        }
        List<FileDescriptor> result = List.copyOf(descriptors);
        lookupCache = new CachedDescriptors(result, System.nanoTime() + LOOKUP_CACHE_TTL.toNanos());
        return result;
    }

    /**
     * Number of subjects skipped (broken schemas) by the most recent
     * {@link #loadDescriptors()} call.
     */
    public int lastSkippedSubjectCount() {
        return lastSkippedSubjects;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Matches by proto file name first, then by fully-qualified message name, then by simple
     * message name (see the class javadoc for the full strategy). Uses a short-lived cache of
     * the compiled registry; call {@link #clearCache()} to force a re-fetch.</p>
     */
    @Override
    public FileDescriptor loadDescriptor(String fileName) throws DescriptorLoadException {
        List<FileDescriptor> descriptors = cachedDescriptors();
        for (FileDescriptor descriptor : descriptors) {
            if (descriptor.getName().equals(fileName)) {
                return descriptor;
            }
        }
        for (FileDescriptor descriptor : descriptors) {
            if (containsMessage(descriptor.getMessageTypes(), fileName, true)) {
                return descriptor;
            }
        }
        for (FileDescriptor descriptor : descriptors) {
            if (containsMessage(descriptor.getMessageTypes(), fileName, false)) {
                return descriptor;
            }
        }
        return null;
    }

    /** Drops the cached compiled registry used by {@link #loadDescriptor(String)}. */
    public void clearCache() {
        lookupCache = null;
    }

    /** Closes the underlying {@link HttpClient}. */
    @Override
    public void close() {
        client.close();
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

    /** Auth failures and server errors mean the registry is unhealthy; abort the whole load. */
    private static boolean abortsLoad(int statusCode) {
        return statusCode == 401 || statusCode == 403 || statusCode >= 500;
    }

    private List<FileDescriptor> cachedDescriptors() throws DescriptorLoadException {
        CachedDescriptors cached = lookupCache;
        if (cached != null && System.nanoTime() - cached.expiresAtNanos() < 0) {
            return cached.descriptors();
        }
        return loadDescriptors();
    }

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

        ProtoSourceSet.Builder sources = ProtoSourceSet.builder();
        files.forEach((path, text) -> sources.add(path, text, "confluent:" + baseUrl + "/" + subject));
        CompiledProtos compiled = compiler.compile(sources.build());
        return compiled.descriptorFor(rootPath)
                .orElseThrow(() -> new DescriptorLoadException(
                        "Compiled schema set does not contain " + rootPath));
    }

    /**
     * Recursively fetches schema references, keyed by their reference {@code name} (the import
     * path). Cycles across {@code subject:version} pairs and dangling (HTTP 404) references
     * abort the enclosing subject (caught and skipped by {@link #loadDescriptors()}); auth and
     * server errors propagate and abort the whole load.
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
                } catch (RegistryHttpException e) {
                    if (abortsLoad(e.statusCode())) {
                        throw e;
                    }
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
                .timeout(requestTimeout)
                .header("Accept", SCHEMA_REGISTRY_ACCEPT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RegistryHttpException(response.statusCode(), path);
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

    private static boolean containsMessage(List<Descriptor> messages, String name, boolean fullyQualified) {
        for (Descriptor message : messages) {
            String candidate = fullyQualified ? message.getFullName() : message.getName();
            if (candidate.equals(name)
                    || containsMessage(message.getNestedTypes(), name, fullyQualified)) {
                return true;
            }
        }
        return false;
    }
}
