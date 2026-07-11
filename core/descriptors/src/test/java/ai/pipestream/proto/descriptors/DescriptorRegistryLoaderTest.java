package ai.pipestream.proto.descriptors;

import ai.pipestream.proto.descriptors.DescriptorLoader;
import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.descriptors.GoogleDescriptorLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DescriptorRegistry integration with DescriptorLoaders.
 */
public class DescriptorRegistryLoaderTest {

    @Test
    void testAutoLoadConstructor() {
        DescriptorRegistry registry = DescriptorRegistry.create(true);
        assertNotNull(registry);

        // Should have well-known types at minimum
        assertNotNull(registry.findDescriptorByFullName("google.protobuf.Struct"));
        assertNotNull(registry.findDescriptorByFullName("google.protobuf.Any"));

        // If descriptor file is available, should have loaded additional types
        int size = registry.size();
        assertTrue(size > 0);
    }

    @Test
    void testAddLoaderAndAutoLoad() throws Exception {
        DescriptorRegistry registry = new DescriptorRegistry();
        int initialSize = registry.size();

        GoogleDescriptorLoader loader = new GoogleDescriptorLoader();
        registry.addLoader(loader);

        if (loader.isAvailable()) {
            registry.autoLoadDescriptors();
            
            // Registry should now have more descriptors
            int newSize = registry.size();
            assertTrue(newSize >= initialSize);
        }
    }

    @Test
    void testBuilderWithGoogleDescriptorLoader() {
        DescriptorRegistry registry = DescriptorRegistry.builder()
            .withGoogleDescriptorLoader()
            .build();

        assertNotNull(registry);
        // Well-known types should be registered
        assertNotNull(registry.findDescriptorByFullName("google.protobuf.Struct"));
    }

    @Test
    void testBuilderWithCustomPath() {
        DescriptorRegistry registry = DescriptorRegistry.builder()
            .withGoogleDescriptorLoader("custom/path.dsc")
            .build();

        assertNotNull(registry);
    }

    @Test
    void testBuilderWithAutoLoad() {
        DescriptorRegistry registry = DescriptorRegistry.builder()
            .withGoogleDescriptorLoader()
            .withAutoLoad()
            .build();

        assertNotNull(registry);
        // Should have attempted to load well-known types at least
        assertNotNull(registry.findDescriptorByFullName("google.protobuf.Struct"));
    }

    @Test
    void testAutoLoadIsIdempotent() {
        DescriptorRegistry registry = new DescriptorRegistry();
        int initialSize = registry.size();

        // Call autoLoad multiple times
        registry.autoLoadDescriptors();
        int afterFirstLoad = registry.size();

        registry.autoLoadDescriptors();
        int afterSecondLoad = registry.size();

        registry.autoLoadDescriptors();
        int afterThirdLoad = registry.size();

        // Size should be stable after first load
        assertEquals(afterFirstLoad, afterSecondLoad);
        assertEquals(afterSecondLoad, afterThirdLoad);
    }

    @Test
    void testAddMultipleLoaders() {
        DescriptorRegistry registry = new DescriptorRegistry();

        GoogleDescriptorLoader loader1 = new GoogleDescriptorLoader();
        GoogleDescriptorLoader loader2 = new GoogleDescriptorLoader("custom/path.dsc");

        registry.addLoader(loader1);
        registry.addLoader(loader2);

        // Should not throw
        registry.autoLoadDescriptors();
    }

    @Test
    void testAddNullLoader() {
        DescriptorRegistry registry = new DescriptorRegistry();
        int initialSize = registry.size();

        registry.addLoader(null);

        // Should handle null gracefully
        assertEquals(initialSize, registry.size());
    }
}
