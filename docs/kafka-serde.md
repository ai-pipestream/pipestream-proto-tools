# Kafka serde

`protomolt-serde` is a Kafka protobuf serializer and deserializer that writes
the Confluent wire format and enforces the schema's own declared rules on the
way out.

The distinction is worth being precise about. Other protobuf serdes check that
the *schema* matches the registry, which is a schema-shape check. This one
checks that the *data* matches the schema, which is what the schema declares.
The rules already live on the descriptor as options, so the serializer
validates before it frames and a message that violates its own contract is
rejected rather than written. Validation stops being something a producer has
to remember to call, because it stops being the application's code path.

## Wire format

Confluent's, implemented from its published specification:

```
[0x00 magic][4-byte big-endian schema id][message-index array][payload]
```

The message-index array locates the message within its schema's file: the
index of the top-level type, then one index per nesting step. Its varints are
zigzag-encoded, and the common single index `[0]` is written as a lone zero
byte. Records written by this serializer are readable by any Confluent-
compatible consumer, and records written by a Confluent producer are readable
by this deserializer.

## Where the schema comes from

A descriptor set the deployment already packages, on the classpath or inline:

```properties
value.serializer=ai.pipestream.proto.kafka.serde.ProtoMoltProtobufSerializer
protomolt.descriptor.set.resource=schemas/orders.desc
protomolt.message.type=acme.orders.v1.Order
```

That alone is a working serde, with no registry involved and no per-record
network hop.

Point it at a Confluent-compatible registry and it will use one:

```properties
protomolt.registry.url=http://localhost:8081
```

On write, the id registered for the subject (`<topic>-value` by default) is
looked up once per topic and stamped into the frame. On read, the frame's id is
resolved to the registry's schema, which is how a consumer follows a topic
whose writers have moved on.

### When the registry cannot answer

The packaged descriptor set is used instead, and the record is still processed.
This is deliberate: a serde that fails when the registry does turns a metadata
service into a hard runtime dependency of every producer and consumer on the
cluster. An unreachable registry should not stop a producer whose schema has
not changed.

Falling back supplies a schema; it does not suspend the contract that schema
declares. Messages are still validated against the packaged schema's rules, and
a frame whose index path disagrees with the configured type is still refused
rather than parsed as the wrong message. The fallback is logged once per serde,
not once per record, because a warning on every message during an outage is a
second outage.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `protomolt.descriptor.set.resource` | | Classpath resource holding a serialized `FileDescriptorSet`. Exactly one of this or the base64 form is required |
| `protomolt.descriptor.set.base64` | | The same, inline |
| `protomolt.message.type` | | Fully qualified message type |
| `protomolt.registry.url` | none | A Confluent-compatible registry, if there is one |
| `protomolt.subject` | `<topic>-value` | Subject to look the id up under |
| `protomolt.schema.id` | `0` | Id stamped when no registry answers |
| `protomolt.validate.on.write` | `true` | Reject invalid messages instead of writing them |
| `protomolt.validate.on.read` | `false` | Validate after deserializing |

Validating on read is off by default. A consumer usually cannot fix what a
producer already wrote, and one that starts rejecting history on upgrade is
worse than one that does not. Turn it on for topics whose producers do not all
come through this serde, which is the only way invalid data gets in.

## Current limitations

- **Schemas are looked up, not registered.** There is no auto-registration: the
  id is something the schema's owner publishes, not something a producer
  decides, and registries are routinely deployed with auto-registration off for
  exactly that reason. A subject the registry does not know falls back to the
  configured id.
- **The subject strategy is TopicNameStrategy**, overridable per serde with
  `protomolt.subject`. Record-name strategies are not implemented.
- **Rules must survive the descriptor set.** Options are only readable as rules
  if they were parsed with their extensions registered; this module registers
  ProtoMolt's validation and metadata extensions when it reads a descriptor set.
  A schema resolved from a registry carries whatever rules its `.proto` text
  declares, which for a schema registered by another tool is typically none.
