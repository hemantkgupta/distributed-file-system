# dfs-client

**Status:** SPEC only. No implementation yet.

Thick client library for the DFS cluster. Holds capabilities, caches the cluster map, buffers writes, page-caches reads, and scatter-gathers I/O directly to OSDs without traversing a gateway. See [`SPEC.md`](./SPEC.md) for the LLM-driveable implementation contract.

Maps to **§6 Client Library (Thick Client)** in the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#6-client-library-thick-client).

## Module layout

```
dfs-client/
├── README.md             # this file
├── SPEC.md               # implementation spec for LLM / human implementer
├── src/main/java/com/hkg/dfs/client/
│                         # implementation lands here
└── src/test/java/com/hkg/dfs/client/
                          # tests land here
```

## Building (after implementation)

```sh
./gradlew :dfs-client:build
./gradlew :dfs-client:test
```
