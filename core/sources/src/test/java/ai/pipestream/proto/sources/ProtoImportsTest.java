package ai.pipestream.proto.sources;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoImportsTest {

    @Test
    void parsesPlainPublicAndWeakImports() {
        String proto = """
                syntax = "proto3";
                import "a/one.proto";
                import public "b/two.proto";
                import weak "c/three.proto";
                message M {}
                """;
        assertThat(ProtoImports.of(proto))
                .containsExactly("a/one.proto", "b/two.proto", "c/three.proto");
    }

    @Test
    void ignoresCommentedOutImports() {
        String proto = """
                syntax = "proto3";
                // import "commented/line.proto";
                /* import "commented/block.proto"; */
                import "real.proto";
                """;
        assertThat(ProtoImports.of(proto)).containsExactly("real.proto");
    }

    @Test
    void ignoresImportsBuriedInMultiLineBlockComments() {
        String proto = """
                /*
                 import "hidden.proto";
                 */
                import "real.proto";
                """;
        assertThat(ProtoImports.of(proto)).containsExactly("real.proto");
    }

    @Test
    void returnsEmptyForNoImports() {
        assertThat(ProtoImports.of("syntax = \"proto3\";\nmessage M {}")).isEmpty();
    }
}
