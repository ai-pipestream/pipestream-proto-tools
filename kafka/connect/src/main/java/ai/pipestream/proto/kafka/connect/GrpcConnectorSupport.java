package ai.pipestream.proto.kafka.connect;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.kafka.connect.errors.ConnectException;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the sink and source connectors share: resolving a {@code package.Service/Method}
 * against a configured descriptor set, building the channel, and the plugin version. The
 * config key names referenced in error messages are identical across both connectors.
 */
final class GrpcConnectorSupport {

    private GrpcConnectorSupport() {
    }

    static String pluginVersion() {
        String version = GrpcConnectorSupport.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    static ManagedChannel channel(String target, boolean plaintext) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(target);
        if (plaintext) {
            builder.usePlaintext();
        }
        return builder.build();
    }

    static MethodDescriptor resolveMethod(String descriptorSetBase64, String qualified) {
        int slash = qualified.indexOf('/');
        if (slash <= 0 || slash == qualified.length() - 1) {
            throw new ConnectException("'grpc.method' must be 'package.Service/Method'; got '"
                    + qualified + "'");
        }
        String serviceName = qualified.substring(0, slash);
        String methodName = qualified.substring(slash + 1);

        FileDescriptorSet set;
        try {
            set = FileDescriptorSet.parseFrom(Base64.getDecoder().decode(descriptorSetBase64));
        } catch (Exception e) {
            throw new ConnectException("'schema.descriptor.set.base64' is not a base64 "
                    + "serialized FileDescriptorSet: " + e.getMessage(), e);
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
