# Pipeline

A pipeline is a protobuf message that chains steps together. Each step hands
its output to the next. Any gRPC service can be a step: the call resolves
through server reflection, so no stubs and no registration are needed. The
other steps are projections (protomolt-projection), CEL filters and
selects, unnest, collect, and variable bindings.

The schema:
[pipeline.proto](../../pipeline/src/main/proto/ai/pipestream/proto/pipeline/v1/pipeline.proto)

The artifact is the message. Humans write it as proto-text. The registry
stores it. gRPC carries it.

## How a run behaves

Steps run in order, in one process, with nothing saved in between. The flow
is a stream of messages of one tracked type; a single message is a stream of
one. The shape of a call follows the method's own streaming flags: a unary
method over a stream runs once per element, a client-streaming method
consumes the flow as its request stream, and server-streaming or bidi
responses become the new flow.

Before a run, a check phase resolves every service, method, type, path, and
CEL expression the pipeline names. Anything that does not resolve or compile
fails there, with the step named.

Every run returns a `RunRecord`: the pipeline name, the input and output
types, and per step the name, elapsed time, messages in, messages out, and
error if one occurred.

## Example

```pb
name: "search-rerank"
input_type: "ai.pipestream.search.v1.SearchRequest"
vars { key: "query" value { string_value: "admissibility of prior acts" } }
steps {
  name: "search"
  grpc_call {
    endpoint { host: "localhost" port: 9090 plaintext: true }
    service: "ai.pipestream.search.KnnService"
    method: "Search"
    input_cel: "input"
  }
}
steps {
  name: "hits"
  unnest { path: "hits" }
}
steps {
  name: "docs"
  project { target_type: "acme.search.v1.SearchDoc" }
}
steps {
  name: "set"
  collect {}
}
steps {
  name: "rerank"
  grpc_call {
    endpoint { host: "localhost" port: 9091 plaintext: true }
    service: "acme.rerank.RerankService"
    method: "Rerank"
    input_cel: "{'query': vars.query, 'docs': input}"
  }
}
```

## Status

The schema is committed. The check phase and the executor are next.
