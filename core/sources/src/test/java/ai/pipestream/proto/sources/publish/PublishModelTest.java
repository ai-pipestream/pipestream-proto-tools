package ai.pipestream.proto.sources.publish;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishModelTest {

    @Test
    void importPathNamingIsIdentity() {
        assertThat(SubjectNamingStrategy.importPath().subjectFor("common/v1/core.proto"))
                .isEqualTo("common/v1/core.proto");
    }

    @Test
    void baseNameNamingStripsDirectoriesAndSuffix() {
        SubjectNamingStrategy naming = SubjectNamingStrategy.baseName();
        assertThat(naming.subjectFor("common/v1/core.proto")).isEqualTo("core");
        assertThat(naming.subjectFor("flat.proto")).isEqualTo("flat");
        assertThat(naming.subjectFor("odd-name")).isEqualTo("odd-name");
    }

    @Test
    void prefixedNamingPrepends() {
        assertThat(SubjectNamingStrategy.prefixed("schemas/").subjectFor("a/b.proto"))
                .isEqualTo("schemas/a/b.proto");
    }

    @Test
    void resultCountsByAction() {
        PublishResult result = new PublishResult(List.of(
                new PublishResult.FileOutcome("a.proto", "a.proto", PublishResult.Action.CREATED, "v1"),
                new PublishResult.FileOutcome("b.proto", "b.proto", PublishResult.Action.UNCHANGED, "v3"),
                new PublishResult.FileOutcome("c.proto", "c.proto", PublishResult.Action.UPDATED, "v2")));
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
        assertThatCode(result::throwIfFailed).doesNotThrowAnyException();
    }

    @Test
    void throwIfFailedSummarizesEveryFailure() {
        PublishResult result = new PublishResult(List.of(
                new PublishResult.FileOutcome("ok.proto", "ok.proto", PublishResult.Action.CREATED, "v1"),
                new PublishResult.FileOutcome("bad.proto", "bad.proto", PublishResult.Action.FAILED, "409 incompatible"),
                new PublishResult.FileOutcome("worse.proto", "worse.proto", PublishResult.Action.FAILED, "422 invalid")));
        assertThatThrownBy(result::throwIfFailed)
                .isInstanceOf(SchemaPublishException.class)
                .hasMessageContaining("2 of 3")
                .hasMessageContaining("bad.proto")
                .hasMessageContaining("409 incompatible")
                .hasMessageContaining("worse.proto");
    }

    @Test
    void dryRunOptionsCarryFlag() {
        assertThat(PublishOptions.dryRunDefaults().dryRun()).isTrue();
        assertThat(PublishOptions.defaults().dryRun()).isFalse();
        PublishOptions custom = PublishOptions.defaults().withNaming(SubjectNamingStrategy.baseName());
        assertThat(custom.naming().subjectFor("x/y.proto")).isEqualTo("y");
    }
}
