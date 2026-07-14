package ai.pipestream.proto.kafka.connect;

import ai.pipestream.proto.grpc.invoke.DynamicGrpcCalls;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The sink task: each delivered record becomes the gRPC method's request message. Unary
 * methods are invoked once per record; client-streaming methods receive the whole delivered
 * batch as one stream and complete it, so the service acknowledges per batch.
 *
 * <p>Transient gRPC statuses (UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED)
 * surface as {@link RetriableException} so the framework redelivers; anything else is a
 * hard {@link ConnectException}. Undecodable record values are {@link DataException}s,
 * which the framework routes by its configured error tolerance (fail or DLQ).</p>
 */
public final class GrpcSinkTask extends SinkTask {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcSinkTask.class);

    private static final EnumSet<Status.Code> RETRIABLE = EnumSet.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.ABORTED);

    /** Test hook: replaces the channel construction. */
    Function<GrpcSinkConfig, ManagedChannel> channelFactory = GrpcSinkTask::channelFor;

    private GrpcSinkConfig config;
    private ManagedChannel channel;
    private MethodDescriptor method;
    private Metadata headers;

    @Override
    public void start(Map<String, String> props) {
        config = new GrpcSinkConfig(props);
        method = resolveMethod(config);
        if (method.isServerStreaming()) {
            throw new ConnectException("Method " + method.getFullName()
                    + " is server-streaming; the sink supports unary and client-streaming methods");
        }
        headers = new Metadata();
        if (config.apiToken() != null) {
            headers.put(Metadata.Key.of("api_token", Metadata.ASCII_STRING_MARSHALLER),
                    config.apiToken());
        }
        channel = channelFactory.apply(config);
        LOG.info("gRPC sink started: {} -> {} ({})", config.target(), config.method(),
                method.isClientStreaming() ? "client-streaming per batch" : "unary per record");
    }

    @Override
    public void put(Collection<SinkRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        List<DynamicMessage> requests = new ArrayList<>(records.size());
        for (SinkRecord record : records) {
            requests.add(decode(record));
        }
        CallOptions options = CallOptions.DEFAULT
                .withDeadlineAfter(config.deadlineMs(), TimeUnit.MILLISECONDS);
        try {
            if (method.isClientStreaming()) {
                DynamicGrpcCalls.callClientStreaming(
                        channel, method, requests.iterator(), options, cloneHeaders());
            } else {
                for (DynamicMessage request : requests) {
                    DynamicGrpcCalls.call(channel, method, request, options, cloneHeaders(), 1);
                }
            }
        } catch (StatusRuntimeException e) {
            if (RETRIABLE.contains(e.getStatus().getCode())) {
                throw new RetriableException("gRPC " + e.getStatus().getCode() + " from "
                        + config.target() + ": " + e.getStatus().getDescription(), e);
            }
            throw new ConnectException("gRPC " + e.getStatus().getCode() + " from "
                    + config.target() + ": " + e.getStatus().getDescription(), e);
        }
    }

    private DynamicMessage decode(SinkRecord record) {
        Object value = record.value();
        if (value == null) {
            throw new DataException("Record value is null (topic " + record.topic()
                    + ", offset " + record.kafkaOffset() + ")");
        }
        try {
            switch (config.valueFormat()) {
                case PROTOBUF -> {
                    return DynamicMessage.parseFrom(method.getInputType(), asBytes(value));
                }
                case CONFLUENT -> {
                    return DynamicMessage.parseFrom(method.getInputType(),
                            ConfluentFraming.payload(asBytes(value)));
                }
                default -> {
                    String json = value instanceof byte[] bytes
                            ? new String(bytes, StandardCharsets.UTF_8)
                            : value.toString();
                    DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.getInputType());
                    JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
                    return builder.build();
                }
            }
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("Record value does not decode as "
                    + method.getInputType().getFullName() + " (" + config.valueFormat()
                    + ", topic " + record.topic() + ", offset " + record.kafkaOffset() + "): "
                    + e.getMessage(), e);
        }
    }

    private static byte[] asBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        throw new DataException("Record value must be byte[] for this format; got "
                + value.getClass().getName()
                + " (use the ByteArrayConverter for value.converter)");
    }

    private Metadata cloneHeaders() {
        Metadata copy = new Metadata();
        copy.merge(headers);
        return copy;
    }

    @Override
    public void stop() {
        if (channel != null) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }

    @Override
    public String version() {
        return GrpcSinkConnector.pluginVersion();
    }

    private static ManagedChannel channelFor(GrpcSinkConfig config) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(config.target());
        if (config.plaintext()) {
            builder.usePlaintext();
        }
        return builder.build();
    }

    static MethodDescriptor resolveMethod(GrpcSinkConfig config) {
        String qualified = config.method();
        int slash = qualified.indexOf('/');
        if (slash <= 0 || slash == qualified.length() - 1) {
            throw new ConnectException("'" + GrpcSinkConfig.METHOD
                    + "' must be 'package.Service/Method'; got '" + qualified + "'");
        }
        String serviceName = qualified.substring(0, slash);
        String methodName = qualified.substring(slash + 1);

        FileDescriptorSet set;
        try {
            set = FileDescriptorSet.parseFrom(
                    Base64.getDecoder().decode(config.descriptorSetBase64()));
        } catch (Exception e) {
            throw new ConnectException("'" + GrpcSinkConfig.DESCRIPTOR_SET
                    + "' is not a base64 serialized FileDescriptorSet: " + e.getMessage(), e);
        }
        for (FileDescriptor file : link(set)) {
            Descriptors.ServiceDescriptor service = file.findServiceByName(
                    serviceName.contains(".")
                            ? serviceName.substring(serviceName.lastIndexOf('.') + 1)
                            : serviceName);
            if (service != null && service.getFullName().equals(serviceName)) {
                MethodDescriptor method = service.findMethodByName(methodName);
                if (method != null) {
                    return method;
                }
            }
        }
        throw new ConnectException("Method '" + qualified
                + "' not found in the configured descriptor set");
    }

    private static List<FileDescriptor> link(FileDescriptorSet set) {
        Map<String, FileDescriptorProto> byName = new LinkedHashMap<>();
        for (FileDescriptorProto proto : set.getFileList()) {
            byName.put(proto.getName(), proto);
        }
        Map<String, FileDescriptor> built = new LinkedHashMap<>();
        try {
            for (FileDescriptorProto proto : set.getFileList()) {
                build(proto, byName, built);
            }
        } catch (Descriptors.DescriptorValidationException e) {
            throw new ConnectException("Descriptor set does not link: " + e.getMessage(), e);
        }
        return List.copyOf(built.values());
    }

    private static FileDescriptor build(FileDescriptorProto proto,
                                        Map<String, FileDescriptorProto> byName,
                                        Map<String, FileDescriptor> built)
            throws Descriptors.DescriptorValidationException {
        FileDescriptor existing = built.get(proto.getName());
        if (existing != null) {
            return existing;
        }
        FileDescriptor[] dependencies = new FileDescriptor[proto.getDependencyCount()];
        for (int i = 0; i < proto.getDependencyCount(); i++) {
            FileDescriptorProto dep = byName.get(proto.getDependency(i));
            if (dep == null) {
                throw new ConnectException("Descriptor set is missing the import '"
                        + proto.getDependency(i) + "'");
            }
            dependencies[i] = build(dep, byName, built);
        }
        FileDescriptor file = FileDescriptor.buildFrom(proto, dependencies);
        built.put(proto.getName(), file);
        return file;
    }
}
