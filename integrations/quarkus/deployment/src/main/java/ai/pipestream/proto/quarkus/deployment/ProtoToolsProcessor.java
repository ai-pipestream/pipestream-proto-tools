package ai.pipestream.proto.quarkus.deployment;

import ai.pipestream.proto.quarkus.ProtoToolsProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

/** Registers the Pipestream protobuf tools extension at Quarkus build time. */
public final class ProtoToolsProcessor {
    private static final String FEATURE = "pipestream-proto-tools";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    ReflectiveClassBuildItem registerProducerForReflection() {
        return ReflectiveClassBuildItem.builder(ProtoToolsProducer.class.getName()).constructors().methods().build();
    }
}
