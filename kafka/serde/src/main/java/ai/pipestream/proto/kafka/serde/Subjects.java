package ai.pipestream.proto.kafka.serde;

/**
 * The subject a topic's schema is registered under. This is Confluent's TopicNameStrategy, the
 * default every Confluent-compatible registry and client already assumes, so a topic written by
 * one of their producers is found under the same name by ours.
 */
final class Subjects {

    private Subjects() {
    }

    static String of(String topic, boolean isKey) {
        return topic + (isKey ? "-key" : "-value");
    }
}
