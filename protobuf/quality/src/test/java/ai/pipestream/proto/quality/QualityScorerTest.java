package ai.pipestream.proto.quality;

import ai.pipestream.proto.quality.testdata.BrokenRules;
import ai.pipestream.proto.quality.testdata.ScoredDoc;
import ai.pipestream.proto.quality.testdata.Unscored;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * The schema declares what "good" means; the scorer just measures. Weights shift the composite,
 * bools coerce, out-of-range formulas clamp, a per-message evaluation failure is a measurement
 * gap rather than a zero or an exception, and CEL that cannot compile against the type is a
 * schema error that throws deterministically.
 */
class QualityScorerTest {

    private final QualityScorer scorer = QualityScorer.create();

    private static ScoredDoc.Builder decent() {
        return ScoredDoc.newBuilder()
                .setTitle("t")
                .setAuthor("a")
                .setBody("b".repeat(50))
                .setDivisor(2);
    }

    @Test
    void scoresDeclaredDimensionsAndWeighsTheComposite() {
        QualityReport report = scorer.score(decent().build());

        assertThat(report.scored()).isTrue();
        assertThat(report.dimensions().get("completeness")).isEqualTo(1.0);
        assertThat(report.dimensions().get("body")).isEqualTo(0.5);
        assertThat(report.dimensions().get("has_title")).isEqualTo(1.0);
        assertThat(report.dimensions().get("fragile")).isEqualTo(0.25);
        // (1*1 + 0.5*2 + 1*1 + 0.25*1) / (1+2+1+1)
        assertThat(report.composite()).isCloseTo(0.65, offset(1e-9));
        assertThat(report.failed()).isEmpty();
    }

    @Test
    void boolsScoreOneOrZeroAndFormulasClamp() {
        QualityReport report = scorer.score(decent()
                .setTitle("")
                .setAuthor("")
                .setBody("b".repeat(1000))
                .build());

        assertThat(report.dimensions().get("has_title")).isEqualTo(0.0);
        // 1000/100 = 10, clamped by the expression itself and by the scorer.
        assertThat(report.dimensions().get("body")).isEqualTo(1.0);
    }

    /** Division by a zero field: this message could not be measured on that dimension. */
    @Test
    void anEvaluationFailureIsAGapNotAZero() {
        QualityReport report = scorer.score(decent().setDivisor(0).build());

        assertThat(report.failed()).containsExactly("fragile");
        assertThat(report.dimensions()).doesNotContainKey("fragile");
        // The composite is the weighted average of what WAS measured.
        assertThat(report.composite()).isCloseTo((1.0 + 0.5 * 2 + 1.0) / 4.0, offset(1e-9));
    }

    @Test
    void aTypeWithNoDimensionsIsNotScored() {
        QualityReport report = scorer.score(Unscored.newBuilder().setAnything("x").build());
        assertThat(report.scored()).isFalse();
        assertThat(report).isSameAs(QualityReport.none());
    }

    /** CEL naming a field the message does not have is a schema error, not a bad score. */
    @Test
    void unCompilableDimensionsThrowDeterministically() {
        assertThatThrownBy(() -> scorer.score(BrokenRules.newBuilder().setPresent("x").build()))
                .isInstanceOf(QualitySchemaException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("does not compile");
    }

    /** The annotation must survive a descriptor linked without the quality extension. */
    @Test
    void readsAnnotationsCarriedOnlyAsUnknownFields() throws Exception {
        Descriptor blind = relinkWithoutExtensions(ScoredDoc.getDescriptor().getFile())
                .findMessageTypeByName("ScoredDoc");
        assertThat(blind.getOptions().getUnknownFields().asMap()).isNotEmpty();

        DynamicMessage message = DynamicMessage.newBuilder(blind)
                .setField(blind.findFieldByName("title"), "t")
                .setField(blind.findFieldByName("author"), "a")
                .setField(blind.findFieldByName("divisor"), 2)
                .build();

        QualityReport report = QualityScorer.create().score(message);
        assertThat(report.scored()).isTrue();
        assertThat(report.dimensions().get("completeness")).isEqualTo(1.0);
    }

    private static FileDescriptor relinkWithoutExtensions(FileDescriptor file) throws Exception {
        List<FileDescriptor> dependencies = new ArrayList<>();
        for (FileDescriptor dependency : file.getDependencies()) {
            dependencies.add(relinkWithoutExtensions(dependency));
        }
        FileDescriptorProto blind = FileDescriptorProto.parseFrom(file.toProto().toByteArray());
        return FileDescriptor.buildFrom(blind, dependencies.toArray(new FileDescriptor[0]));
    }
}
