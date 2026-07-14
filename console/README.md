# ProtoMolt console

The schema-registry console: a Vue 3 / Vuetify application for browsing
subjects and versions, exploring types, diffing versions, checking
compatibility, and trying the verbs — ProtoMolt's own frontend.

## Running it

```shell
npm install
npm run dev          # against protomolt-serve's registry on localhost:8081
PROTOMOLT_REGISTRY_URL=http://host:port npm run dev   # any Confluent-compatible registry
npm test             # 66 tests, vitest (jsdom for component suites)
npm run build        # static bundle in dist/
```

The dev server proxies `/api/protomolt/*` to the registry, so the app is
same-origin in development; a production deployment serves `dist/` behind
any reverse proxy that does the same.

## Provenance

The application source (views, components, services, tests) was originally
written inside the platform frontend's working tree by mistake and recovered
from the snapshot taken before that tree was reset; this scaffold makes it a
standalone app. `reference/platform-integration.patch` records how it was
once mounted in the platform, and `reference/schemaRegistryProxy.ts` is the
BFF proxy the vite dev proxy replaces. The schema-form and descriptor
utilities under `src/lib/` are vendored from the platform packages they came
from.
