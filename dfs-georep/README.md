# dfs-georep

**Status:** SPEC only. No implementation yet.

Async geo-replication service for the DFS cluster. Ships sealed extents (chunk data) and journal records (metadata) from one region to another over a pluggable WAN channel; receives them on the target side and applies them idempotently. See [`SPEC.md`](./SPEC.md) for the LLM-driveable implementation contract.

Maps to **§9 Geo-Replication Service (Async Log Shipper)** in the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#9-geo-replication-service-async-log-shipper).

## Module layout

```
dfs-georep/
├── README.md             # this file
├── SPEC.md               # implementation spec for LLM / human implementer
├── src/main/java/com/hkg/dfs/georep/
│                         # implementation lands here
└── src/test/java/com/hkg/dfs/georep/
                          # tests land here
```

## Building (after implementation)

```sh
./gradlew :dfs-georep:build
./gradlew :dfs-georep:test
```
