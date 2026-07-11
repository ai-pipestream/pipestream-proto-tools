package ai.pipestream.proto.quarkus.deployment;

import ai.pipestream.proto.quarkus.ProtoToolsProducer;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoToolsProcessorTest {

    @Test
    void registersFeatureAndReflection() {
        ProtoToolsProcessor processor = new ProtoToolsProcessor();
        FeatureBuildItem feature = processor.feature();
        assertThat(feature.getName()).isEqualTo("pipestream-proto-tools");

        ReflectiveClassBuildItem reflective = processor.registerProducerForReflection();
        assertThat(reflective.getClassNames()).contains(ProtoToolsProducer.class.getName());
    }
}
