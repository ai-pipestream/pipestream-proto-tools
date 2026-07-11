package ai.pipestream.proto.rest;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoRestAnnotationRegistrarTest {

    @ProtoRestExposed(summary = "Demo echo service")
    @ProtoApiToken(name = "api_token")
    static final class EchoService {
        @ProtoRestExposed(summary = "Echo a name")
        public Struct echo(Struct request) {
            String name = request.getFieldsOrDefault("name", Value.getDefaultInstance()).getStringValue();
            return Struct.newBuilder()
                    .putFields("message", Value.newBuilder().setStringValue("hello " + name).build())
                    .build();
        }

        public Struct ignored(Struct request) {
            return request;
        }
    }

    @Test
    void registersAnnotatedMethodsOnly() {
        ProtoRestMethodRegistry registry = new ProtoRestMethodRegistry();
        List<ProtoRestMethod> registered = new ProtoRestAnnotationRegistrar(registry)
                .register(new EchoService());

        assertThat(registered).hasSize(1);
        ProtoRestMethod method = registered.getFirst();
        assertThat(method.serviceName()).isEqualTo("Echo");
        assertThat(method.methodName()).isEqualTo("echo");
        assertThat(method.apiToken()).isPresent();
        assertThat(method.summary()).contains("Echo a name");

        assertThat(registry.find("Echo", "echo")).isPresent();
        assertThat(registry.find("Echo", "ignored")).isEmpty();
    }
}
