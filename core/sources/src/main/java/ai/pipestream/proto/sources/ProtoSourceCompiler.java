package ai.pipestream.proto.sources;

import ai.pipestream.proto.descriptors.GoogleDescriptorLoader;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;
import com.squareup.wire.schema.internal.SchemaEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Compiles a {@link ProtoSourceSet} to runtime descriptors using Square Wire's schema library —
 * no {@code protoc} binary involved.
 *
 * <p>Sources are written to a scratch directory, linked with Wire (which supplies the bundled
 * {@code google/protobuf} imports; {@code field_mask.proto}, which Wire does not bundle, is
 * supplied here), encoded to {@code FileDescriptorProto}s and built into linked
 * {@link FileDescriptor}s. Well-known-type imports the encoder cannot supply are linked from
 * protobuf-java's runtime by {@link GoogleDescriptorLoader}'s fallback.</p>
 *
 * <p>Instances are stateless and thread-safe. This is the single compilation pipeline behind
 * every text-based descriptor source (schema-registry loaders, gatherers).</p>
 */
public final class ProtoSourceCompiler {

    private static final Logger LOG = LoggerFactory.getLogger(ProtoSourceCompiler.class);

    /** {@code field_mask.proto} is a well-known import that Wire does not bundle. */
    private static final String FIELD_MASK_PATH = "google/protobuf/field_mask.proto";
    private static final String FIELD_MASK_PROTO = """
            syntax = "proto3";
            package google.protobuf;
            message FieldMask {
              repeated string paths = 1;
            }
            """;

    /**
     * Compiles every file in the set plus the imports Wire can supply.
     *
     * @throws ProtoCompilationException on parse or link failure, unresolvable imports,
     *         unsafe source paths, or scratch-directory I/O failure
     */
    public CompiledProtos compile(ProtoSourceSet sources) throws ProtoCompilationException {
        if (sources.isEmpty()) {
            return new CompiledProtos(FileDescriptorSet.getDefaultInstance(), List.of(), Map.of());
        }
        Path dir;
        try {
            dir = Files.createTempDirectory("protomolt-proto-compile");
        } catch (IOException e) {
            throw new ProtoCompilationException("Failed to create scratch directory", e);
        }
        try {
            writeSources(sources, dir);
            Schema schema = link(dir);
            Map<String, FileDescriptorProto> protos = new LinkedHashMap<>();
            SchemaEncoder encoder = new SchemaEncoder(schema);
            for (String path : sources.paths()) {
                encodeWithDependencies(path, schema, encoder, protos);
            }
            FileDescriptorSet set = FileDescriptorSet.newBuilder().addAllFile(protos.values()).build();
            return linkDescriptors(sources, set);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void writeSources(ProtoSourceSet sources, Path dir) throws ProtoCompilationException {
        boolean needsFieldMask = !sources.contains(FIELD_MASK_PATH);
        try {
            for (ProtoSource source : sources.sources()) {
                Path target = dir.resolve(source.path()).normalize();
                if (!target.startsWith(dir)) {
                    throw new ProtoCompilationException("Unsafe source path: " + source.path()
                            + " (origin: " + source.origin() + ")");
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, source.content());
            }
            if (needsFieldMask) {
                Path fieldMask = dir.resolve(FIELD_MASK_PATH);
                Files.createDirectories(fieldMask.getParent());
                Files.writeString(fieldMask, FIELD_MASK_PROTO);
            }
        } catch (IOException e) {
            throw new ProtoCompilationException("Failed to stage sources for compilation", e);
        }
    }

    private static Schema link(Path dir) throws ProtoCompilationException {
        try {
            SchemaLoader loader = new SchemaLoader(FileSystems.getDefault());
            loader.setLoadExhaustively(true);
            loader.initRoots(List.of(Location.get(dir.toString())), List.of());
            return loader.loadSchema();
        } catch (Exception e) {
            throw new ProtoCompilationException("Failed to link proto sources: " + e.getMessage(), e);
        }
    }

    /**
     * Encodes {@code path} and, transitively, every import Wire has a linked file for. Imports
     * Wire cannot supply (well-known types resolved from protobuf-java's runtime) are left to
     * {@link GoogleDescriptorLoader}'s well-known-type fallback.
     */
    private static void encodeWithDependencies(String path, Schema schema, SchemaEncoder encoder,
                                               Map<String, FileDescriptorProto> out)
            throws ProtoCompilationException {
        if (out.containsKey(path)) {
            return;
        }
        ProtoFile protoFile = schema.protoFile(path);
        if (protoFile == null) {
            return;
        }
        FileDescriptorProto proto;
        try {
            proto = FileDescriptorProto.parseFrom(encoder.encode(protoFile).toByteArray());
        } catch (IOException e) {
            throw new ProtoCompilationException("Failed to encode " + path, e);
        }
        out.put(path, proto);
        for (String dependency : proto.getDependencyList()) {
            encodeWithDependencies(dependency, schema, encoder, out);
        }
    }

    private static CompiledProtos linkDescriptors(ProtoSourceSet sources, FileDescriptorSet set)
            throws ProtoCompilationException {
        List<FileDescriptor> descriptors;
        try {
            descriptors = GoogleDescriptorLoader.fromDescriptorSet(set);
        } catch (Exception e) {
            throw new ProtoCompilationException("Failed to build descriptors: " + e.getMessage(), e);
        }
        Map<String, FileDescriptor> byPath = new HashMap<>();
        for (FileDescriptor descriptor : descriptors) {
            byPath.put(descriptor.getName(), descriptor);
        }
        for (String path : sources.paths()) {
            if (!byPath.containsKey(path)) {
                throw new ProtoCompilationException("Compiled set does not contain " + path);
            }
        }
        return new CompiledProtos(set, descriptors, byPath);
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
}
