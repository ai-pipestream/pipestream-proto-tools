package ai.pipestream.proto.descriptors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Message;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * A descriptor loader that attempts to resolve descriptors from generated 
 * Protobuf classes on the classpath.
 */
public class ClasspathDescriptorLoader implements DescriptorLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathDescriptorLoader.class);

    @Override
    public List<FileDescriptor> loadDescriptors() throws DescriptorLoadException {
        // Classpath scanning for ALL descriptors is expensive and usually not needed
        // since we prefer on-demand resolution via loadDescriptor(typeName).
        return Collections.emptyList();
    }

    @Override
    public FileDescriptor loadDescriptor(String typeName) throws DescriptorLoadException {
        try {
            // Try to find the class on the classpath
            Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(typeName);
            
            // Check if it's a Protobuf message
            if (Message.class.isAssignableFrom(clazz)) {
                Method getDescriptorMethod = clazz.getMethod("getDescriptor");
                Object descriptor = getDescriptorMethod.invoke(null);
                
                if (descriptor instanceof com.google.protobuf.Descriptors.Descriptor d) {
                    LOG.debug("Successfully resolved descriptor for {} from classpath class", typeName);
                    return d.getFile();
                }
            }
        } catch (ClassNotFoundException e) {
            // Normal case if the class is not on the classpath
            LOG.trace("Class {} not found on classpath for descriptor resolution", typeName);
        } catch (Exception e) {
            LOG.warn("Failed to resolve descriptor from class {}: {}", typeName, e.getMessage());
        }
        return null;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getLoaderType() {
        return "Classpath Class Resolver";
    }
}
