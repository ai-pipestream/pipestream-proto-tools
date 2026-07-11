package ai.pipestream.proto.rest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a gRPC service or method as exposed over the JSON/REST gateway.
 * Framework glue (Quarkus/Spring/Micronaut) discovers these and registers routes.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ProtoRestExposed {

    /** Optional path override; empty = derive from service/method names. */
    String path() default "";

    /** HTTP methods allowed (default POST, matching Micronaut grpc-json). */
    String[] httpMethods() default {"POST"};

    /** Human-readable summary for OpenAPI. */
    String summary() default "";

    /** Longer description for OpenAPI. */
    String description() default "";
}
