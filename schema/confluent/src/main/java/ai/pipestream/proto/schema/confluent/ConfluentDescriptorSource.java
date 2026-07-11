package ai.pipestream.proto.schema.confluent;

import ai.pipestream.proto.descriptors.DescriptorLoader;
import ai.pipestream.proto.descriptors.GoogleDescriptorLoader;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;

/**
 * Loads a protobuf FileDescriptorSet exposed by a Confluent-compatible endpoint.
 *
 * <p>Confluent Schema Registry's native protobuf APIs return schema text and
 * references; use an endpoint that supplies a compiled FileDescriptorSet, or a
 * classpath descriptor-set export produced from the registered schema.</p>
 */
public final class ConfluentDescriptorSource implements DescriptorLoader {
    private final URI endpoint;
    private final String classpathResource;
    private final HttpClient client;

    public ConfluentDescriptorSource(URI endpoint) {
        this(endpoint, null, HttpClient.newHttpClient());
    }

    public ConfluentDescriptorSource(String classpathResource) {
        this(null, Objects.requireNonNull(classpathResource, "classpathResource"), HttpClient.newHttpClient());
    }

    private ConfluentDescriptorSource(URI endpoint, String classpathResource, HttpClient client) {
        this.endpoint = endpoint;
        this.classpathResource = classpathResource;
        this.client = client;
    }

    @Override
    public List<FileDescriptor> loadDescriptors() throws DescriptorLoadException {
        try {
            return GoogleDescriptorLoader.fromDescriptorSet(FileDescriptorSet.parseFrom(readBytes()));
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DescriptorLoadException("Failed to load Confluent descriptor set", e);
        } catch (Exception e) {
            throw new DescriptorLoadException("Invalid Confluent descriptor set", e);
        }
    }

    @Override
    public FileDescriptor loadDescriptor(String fileName) throws DescriptorLoadException {
        return loadDescriptors().stream().filter(descriptor -> descriptor.getName().equals(fileName)).findFirst().orElse(null);
    }

    @Override
    public boolean isAvailable() {
        return endpoint != null || Thread.currentThread().getContextClassLoader().getResource(classpathResource) != null;
    }

    @Override
    public String getLoaderType() {
        return "Confluent Schema Registry descriptor set";
    }

    private byte[] readBytes() throws IOException, InterruptedException, DescriptorLoadException {
        if (endpoint != null) {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(endpoint).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new DescriptorLoadException("Confluent endpoint returned HTTP " + response.statusCode());
            }
            return response.body();
        }
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new DescriptorLoadException("Classpath descriptor set not found: " + classpathResource);
            }
            return input.readAllBytes();
        }
    }
}
