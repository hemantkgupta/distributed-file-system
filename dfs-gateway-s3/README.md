# dfs-gateway-s3

**Status:** SPEC only. No implementation yet.

S3 REST gateway for the DFS cluster. See [`SPEC.md`](./SPEC.md) for the LLM-driveable implementation contract.

Maps to **§7 Object Gateway** in the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#7-object-gateway-s3-rest--swift--rgw-class).

## Module layout

```
dfs-gateway-s3/
├── README.md             # this file
├── SPEC.md               # implementation spec for LLM / human implementer
├── src/main/java/com/hkg/dfs/gateway/s3/
│                         # implementation lands here
└── src/test/java/com/hkg/dfs/gateway/s3/
                          # tests land here
```

## Building (after implementation)

```sh
./gradlew :dfs-gateway-s3:build
./gradlew :dfs-gateway-s3:test
```
