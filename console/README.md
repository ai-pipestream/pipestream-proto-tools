# ProtoMolt console

The schema-registry console: a Vue 3 / Vuetify application for browsing
subjects and versions, exploring types, diffing versions, checking
compatibility, and trying the verbs — ProtoMolt's own frontend.

## Status: recovered source, not yet wired to a build

This code was originally written inside the platform frontend's working tree
by mistake (it was never committed there); the sole copy was snapshotted and
has now been landed here, in the project it belongs to. What's present:

- `src/` — the application: views (`SubjectsView`, `SubjectDetailView`),
  components (type explorer, version diff, compat check, try-it panel, proto
  source with highlighting), services (`api`, `descriptorModel`,
  `compatCheck`, `protoHighlight`, `textDiff`, `routes`), and their tests
  (vitest + jsdom, via `componentTestKit.ts`).
- `schemaRegistryProxy.ts` (+ test) — the reverse proxy the platform BFF used
  for `/api/protomolt/*`. Standalone, the console should instead target
  `protomolt-serve`'s registry and REST ports directly (or this proxy can
  become a slim dev server).
- `descriptor.ts` — the descriptor-parsing entry that lived in the platform's
  `protobuf-forms` package.
- `platform-integration.patch` — the full diff of how it was mounted in the
  platform monorepo (router entry, BFF registration, package deps), kept for
  reference while extracting.

To make it a standalone app it still needs: a `package.json` + vite/vitest
scaffold (the platform's hosted these), dependency selection (the patch
records what it used), and an API base pointing at a running
`protomolt-serve` instead of the platform BFF.
