# Joins, unions, and derived shapes (design)

Status: design settled; phase 1 (multi-source resolution, shape synthesis,
the `synthesize-shape` and `join-messages` verbs) is implemented.

## Motivation

ETL systems live and die by their in-between steps, and two of those steps
are non-negotiable: **join** (two inputs, one enriched output) and
**union** (many similar inputs, one stream). ProtoMolt already has the
manipulation half — mapping rules, CEL, validation. This design adds the
combination half, protobuf-natively: what is the *shape* of a join's
output, how are two messages resolved into it, and where does the derived
schema live?

## The shape problem

In SQL a join's output shape is ephemeral — the SELECT list projects
columns and the "type" evaporates after the query. Protobuf refuses that:
anything downstream needs a real descriptor. So a join's output must be a
declared message type, and there are exactly three honest ways to get one:

1. **Authored.** The output message is written by hand in a `.proto`, and
   mapping rules populate it from the sources. The rules are the SELECT
   list. Explicit, reviewed, versioned — the right choice for contracts
   that outlive the join.
2. **Synthesized envelope.** A generated wrapper holding each source
   intact: `message Joined { shop.v1.Order order = 1; crm.v1.Customer
   customer = 2; }`. Lossless and zero-authoring; the shape is machine-made
   and consumers navigate nesting.
3. **Synthesized projection.** A generated flat message whose fields are
   *derived from source paths*: `customer_name` gets its type from
   `customer.name`'s field descriptor. The SELECT list becomes the schema.

Union splits the same way:

- **Structural union** (SQL `UNION`): N similar types projected onto one
  common target — an authored or synthesized target plus one ruleset per
  source type.
- **Tagged union**: protobuf's native answer, a synthesized `oneof` over
  the source types. Lossless, and consumers dispatch on the case.

## Derived schemas are real schemas

The move that makes this ProtoMolt-shaped rather than generic-ETL-shaped:
synthesized shapes are built as descriptors (a `FileDescriptorProto`
depending on the sources' files), compiled in-process, and emitted as
`.proto` source whose imports are the sources' true import paths. That
text registers in the Git-backed registry like any hand-written schema —
with references, history, diffs, and compatibility gates. A join's output
contract stops being implicit in a transformation config and becomes a
governed schema. When the join definition changes, `check-compat` says
whether downstream consumers survive it.

## Multi-source resolution

One resolution model powers everything: a **scope** of named messages.
Text rules read `target.path = source.path` where the first segment of the
source path names a scope entry; CEL expressions see each scope entry as a
variable. The same scope shape appears everywhere combination happens:

| Context | Scope entries |
|---|---|
| Join | `order`, `customer`, … (the named sources) |
| Chain step | `input`, `steps.<name>` |
| Enrich transform | `input`, `response` |
| Key+value record join | `key`, `value` |

Learned once, used everywhere. Synthesized projections carry their implied
rules (field name ← source path), so projecting needs no hand-written
ruleset; authored targets take explicit rules, exactly the `map-message`
surface with scoped source paths.

## Execution stories

- **The verbs** — `synthesize-shape` (sources + mode + name → proto
  source, descriptor set, implied rules) and `join-messages` (sources with
  messages + a target or shape spec + rules → the joined message). Verbs
  mean every surface: typed RPCs, REST, Swagger, MCP tools for agents.
- **Chains** — the chain variable scope *is* a multi-source scope, so a
  chain's output mapping is already a join of every step's response.
- **gRPC streams — our streaming turf.** Stateful topic-to-topic joins
  belong to Kafka Streams. But when both sides are *gRPC server streams*,
  the join is ours: open two flow-controlled `DynamicGrpcStream`s, match
  pairwise (zip) or by key within a bounded buffer, emit the joined shape.
  Flow control comes free — each side is only requested as it drains, so
  a fast stream cannot flood a slow one; bounded buffers plus a wait
  budget make memory explicit, and unmatched entries expire by policy
  (drop, emit-partial, or fail). This is a planned phase, not yet built.
- **Connect transforms** — a record's key and value messages joined into
  the value (the smallest useful join); `GrpcEnrich` as the lookup join
  where the other side lives behind a service.

## Keys declared in the schema

Joins need keys, and the schema should say what they are. A declared-key
field option in the metadata standards (`ai.pipestream.proto.meta.v1`)
lets a message state its identity once; key-based stream joins, dedup,
Kafka record keys, and upsert semantics all derive from it instead of
being configured per deployment. Same philosophy as validation rules and
indexing hints: declare in the contract, honor on every surface. Planned
alongside the stream-join phase.

## Non-goals

- **Stateful topic-to-topic joins** — windows, co-partitioning, state
  stores. Kafka Streams does this well; a sidecar should not.
- **Unbounded buffering** — stream joins always carry explicit bounds and
  an expiry policy. If a join needs unbounded state, it needs a database.
- **A query language** — rules and CEL are the surface. If a SQL-ish
  layer ever makes sense, it compiles down to these primitives (the same
  posture as the chain manager's contract-as-language direction).

## Phasing

1. **Shapes and verbs** (implemented): `protomolt-shapes` — scope,
   scoped mapper, synthesizer (envelope, projection, tagged union),
   joiner — plus the `synthesize-shape` / `join-messages` verbs on every
   surface.
2. **Stream joins**: zip and keyed joins over two `DynamicGrpcStream`s
   with bounded buffers; a Connect source that emits a joined stream.
3. **Schema-declared keys**: the metadata option and its adoption by
   stream joins and the connectors.
4. **Registry registration of derived shapes** as a first-class flow
   (register the synthesized source with references in one call).
