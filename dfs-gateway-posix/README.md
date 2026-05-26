# dfs-gateway-posix

**Status:** SPEC only. No implementation yet.

POSIX protocol gateway for the DFS cluster. Exposes the cluster to applications that cannot or will not link the thick client library. The initial pass implements **NFSv3** as the primary protocol head and **Kubernetes CSI** as an optional secondary head; **SMB is out of scope**.

See [`SPEC.md`](./SPEC.md) for the LLM-driveable implementation contract.

Maps to **§8 POSIX Gateway** in the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#8-posix-gateway-nfs--smb--csi-driver).

## Module layout

```
dfs-gateway-posix/
├── README.md             # this file
├── SPEC.md               # implementation spec for LLM / human implementer
├── src/main/java/com/hkg/dfs/gateway/posix/
│                         # implementation lands here
└── src/test/java/com/hkg/dfs/gateway/posix/
                          # tests land here
```

## Building (after implementation)

```sh
./gradlew :dfs-gateway-posix:build
./gradlew :dfs-gateway-posix:test
```
