# Kafka Connect

`protomolt-connect` is a Kafka Connect plugin that joins topics and gRPC
services in both directions, descriptor-native with no generated stubs:

- **`GrpcSinkConnector`** — records from subscribed topics become request
  messages on a configured gRPC method. Unary methods are called once per
  record; client-streaming methods receive each delivered batch as one
  stream.
- **`GrpcSourceConnector`** — a server-streaming method feeds a topic. The
  stream's flow control is the poll loop's pace, so an open-ended
  (Watch-style) stream is consumed indefinitely with a bounded buffer, and a
  CEL-extracted resume token stored as the Connect offset lets the
  subscription pick up where Kafka left off after a restart.

Both connectors are configured with a serialized
`google.protobuf.FileDescriptorSet` — no proto files on the worker, no code
generation, no rebuild when the schema changes. The classes live in
`ai.pipestream.proto.kafka.connect`.

## Getting the descriptor set

Any of ProtoMolt's surfaces produces the base64 descriptor set the
connectors take:

- the `compile` verb returns `descriptorSetBase64` for inline or gathered
  sources;
- the `reflect` verb captures it from a live server's reflection service;
- the registry serves it per subject:
  `GET {nativePrefix}/subjects/{subject}/descriptor-set` (base64-encode the
  binary response).

## Installation

Kafka Connect loads plugins from directories on the worker's `plugin.path`.
Place the `protomolt-connect` jar and its runtime dependencies together in
one such directory:

```bash
./gradlew :protomolt-connect:jar
# copy kafka/connect/build/libs/protomolt-connect-*.jar plus its runtime
# dependencies into <plugin.path>/protomolt-connect/
```

The Connect framework itself (`connect-api`) is provided by the worker and
is not bundled.

## The sink

```json
{
  "name": "orders-to-grpc",
  "config": {
    "connector.class": "ai.pipestream.proto.kafka.connect.GrpcSinkConnector",
    "topics": "orders",
    "grpc.target": "order-service:9090",
    "grpc.method": "shop.v1.OrderService/Record",
    "schema.descriptor.set.base64": "CvQBCg9zaG9wL3YxL29yZGVy...",
    "value.format": "protobuf",
    "value.converter": "org.apache.kafka.connect.converters.ByteArrayConverter"
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `grpc.target` | — | gRPC target, e.g. `host:9090` or `dns:///svc:443` |
| `grpc.method` | — | `package.Service/Method`; unary or client-streaming |
| `schema.descriptor.set.base64` | — | Serialized `FileDescriptorSet` declaring the service |
| `value.format` | `protobuf` | `protobuf` (raw message bytes), `confluent` (framed with a schema id, as Confluent serializers write), or `json` (canonical proto3 JSON text) |
| `grpc.deadline.ms` | `30000` | Per call (unary) or per delivered batch (client-streaming) |
| `grpc.api.token` | — | Optional shared secret sent as `api_token` metadata |
| `grpc.plaintext` | `true` | Set `false` for TLS |

Unary methods are invoked once per record. Client-streaming methods receive
the whole delivered batch as one stream and complete it, so the service
acknowledges per batch — the natural fit when the service aggregates.

Error semantics follow Connect conventions: transient gRPC statuses
(`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`) raise
`RetriableException` so the framework redelivers; any other status fails the
task. A record value that does not decode as the request message raises
`DataException`, which the worker routes by its configured error tolerance —
fail, skip, or dead-letter queue.

## The source

```json
{
  "name": "grpc-to-ticks",
  "config": {
    "connector.class": "ai.pipestream.proto.kafka.connect.GrpcSourceConnector",
    "topic": "ticks",
    "grpc.target": "feed-service:9090",
    "grpc.method": "feed.v1.FeedService/Watch",
    "schema.descriptor.set.base64": "CvQBCg9mZWVkL3YxL2ZlZWQ...",
    "grpc.request.json": "{\"shard\": \"us-east\"}",
    "resume.token.cel": "input.cursor",
    "resume.token.request.field": "resume_token",
    "value.converter": "org.apache.kafka.connect.converters.ByteArrayConverter"
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `grpc.target` | — | gRPC target |
| `grpc.method` | — | `package.Service/Method`; must be server-streaming |
| `schema.descriptor.set.base64` | — | Serialized `FileDescriptorSet` declaring the service |
| `topic` | — | Topic the streamed messages are written to |
| `grpc.request.json` | `{}` | The subscribe request, as canonical proto3 JSON |
| `resume.token.cel` | — | CEL over each streamed message (bound as `input`) yielding its resume token |
| `resume.token.request.field` | — | Dotted path of a string field in the request where the stored token is injected on (re)subscribe |
| `record.key.cel` | — | Optional CEL yielding the record key as a string |
| `value.format` | `protobuf` | `protobuf` bytes (pair with the `ByteArrayConverter`) or proto3 `json` text (pair with the `StringConverter`) |
| `poll.max.records` | `500` | Maximum records per poll |
| `poll.timeout.ms` | `1000` | How long a poll waits for the stream before returning what it has |
| `reconnect.backoff.ms` | `1000` | Pause before resubscribing after the stream ends or fails transiently |
| `grpc.api.token` | — | Optional shared secret sent as `api_token` metadata |
| `grpc.plaintext` | `true` | Set `false` for TLS |

### Resume tokens

Server streams have no Kafka-style offsets, so the source manufactures
them: `resume.token.cel` is evaluated over each streamed message and the
result rides along as the record's Connect offset. On task (re)start the
last committed token is read back and injected into the subscribe request
at `resume.token.request.field`, so a server designed for resumption (a
cursor, a change-stream token, a sequence number rendered as a string)
continues where Kafka left off.

Delivery is at-least-once. After a mid-stream failure the task resubscribes
from the latest token it *emitted*, which may replay messages Kafka already
committed; consumers should treat the token (or the message identity) as
their deduplication key. Without `resume.token.cel` the source has no
offsets and every restart subscribes exactly as `grpc.request.json`
configures.

The stream is one subscription, so the connector always runs a single task
regardless of `tasks.max` — a second subscriber would duplicate every
message. Transient statuses and graceful stream completions resubscribe
after `reconnect.backoff.ms`; any other status fails the task. Everything
that can be validated — the method's shape, the request template, the token
field path, the CEL expressions (type-checked against the stream's message
type) — is validated at connector start, not first poll.
