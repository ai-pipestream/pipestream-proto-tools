package ai.pipestream.proto.schema.apicurio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApicurioDescriptorLoaderTest {

    @Test
    void builderRequiresRegistryUrl() {
        assertThatThrownBy(() -> ApicurioDescriptorLoader.builder().groupId("g").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Registry URL");
    }

    @Test
    void builderRequiresGroupId() {
        assertThatThrownBy(() -> ApicurioDescriptorLoader.builder()
                .registryUrl("http://localhost:8080")
                .groupId(" ")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Group ID");
    }

    @Test
    void nullClientIsUnavailable() {
        ApicurioDescriptorLoader loader = ApicurioDescriptorLoader.builder()
                .registryUrl("http://localhost:8080/apis/registry/v3")
                .groupId("default")
                .registryClient(null)
                .build();
        assertThat(loader.isAvailable()).isFalse();
        assertThat(loader.getLoaderType()).contains("Apicurio");
        assertThat(loader.getGroupId()).isEqualTo("default");
        assertThatThrownBy(loader::loadDescriptors)
                .isInstanceOf(ai.pipestream.proto.descriptors.DescriptorLoader.DescriptorLoadException.class);
    }
}
