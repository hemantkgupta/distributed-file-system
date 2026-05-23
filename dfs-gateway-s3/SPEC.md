# dfs-gateway-s3 — LLM Implementation Spec

> **Status:** SPEC. No code yet. Generate against this; tick off the checklist in §11 as code lands.
>
> **Maps to:** §7 Object Gateway in the [full essay](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system-full.md#7-object-gateway-s3-rest--swift--rgw-class) and the [service catalog](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md#7-object-gateway-s3-rest--swift--rgw-class).
>
> **Out of scope for this implementation pass:** Swift protocol, advanced IAM (assume static `{accessKey → secretKey}` map), real KMS, bucket versioning enforcement, lifecycle policy enforcement, cross-region replication routing. These are called out per-section below.

---

## 1. Purpose

Expose the DFS cluster via the AWS S3 REST API. Translate S3 verbs (PUT, GET, DELETE, multipart, list) into operations on the client library, which in turn talks to the MDS and OSDs.

The gateway is **stateless**: every request is a fresh client-library session. Auth, IAM logic, bucket-to-namespace mapping, multipart-upload coordination, and listing index maintenance are this service's responsibilities. Byte durability is the underlying file system's.

---

## 2. Position in the system

- **Upstream consumers:** HTTP clients (curl, `aws-cli`, S3 SDKs in any language).
- **Downstream dependencies:**
  - **§6 Client Library** (`dfs-client`, not yet built — see §10 for the mock contract this spec uses in the interim).
  - **`dfs-common`** for shared value types.
  - **`dfs-security`** (optional, stubbed in initial pass) for KMS-style operations.
- **Sibling coordination:**
  - The Custodian (§5) periodically cleans up expired multipart records — this gateway just sets the expiry, doesn't run the cleanup itself.

---

## 3. Public API surface

### 3.1 HTTP surface (what S3 clients see)

```http
PUT    /{bucket}/{key}                                # single-part object put
GET    /{bucket}/{key}                                # object get
HEAD   /{bucket}/{key}                                # object metadata only
DELETE /{bucket}/{key}                                # object delete
GET    /{bucket}?list-type=2&prefix={p}&continuation-token={t}&max-keys={n}
POST   /{bucket}/{key}?uploads                        # multipart init
PUT    /{bucket}/{key}?partNumber=N&uploadId={uid}    # multipart part
POST   /{bucket}/{key}?uploadId={uid}                 # multipart complete
DELETE /{bucket}/{key}?uploadId={uid}                 # multipart abort
PUT    /{bucket}                                      # create bucket
DELETE /{bucket}                                      # delete bucket (must be empty)
GET    /                                              # list buckets for the auth principal
```

Every request must carry an `Authorization` header in AWS Signature Version 4 format. Bad signature → `403 Forbidden`.

Responses follow the standard S3 XML format. Errors use the [S3 REST error response](https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html) shape:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Error>
  <Code>NoSuchBucket</Code>
  <Message>The specified bucket does not exist</Message>
  <Resource>/{bucket}</Resource>
  <RequestId>{uuid}</RequestId>
</Error>
```

### 3.2 Java API surface (what other modules call)

```java
package com.hkg.dfs.gateway.s3;

/** Programmatic surface — same operations the HTTP layer exposes. */
public interface S3Gateway {
    PutObjectResponse      putObject     (PutObjectRequest req);
    GetObjectResponse      getObject     (GetObjectRequest req);
    HeadObjectResponse     headObject    (HeadObjectRequest req);
    DeleteObjectResponse   deleteObject  (DeleteObjectRequest req);
    ListObjectsResponse    listObjects   (ListObjectsRequest req);

    CreateMultipartResponse   createMultipart   (CreateMultipartRequest req);
    UploadPartResponse        uploadPart        (UploadPartRequest req);
    CompleteMultipartResponse completeMultipart (CompleteMultipartRequest req);
    AbortMultipartResponse    abortMultipart    (AbortMultipartRequest req);

    CreateBucketResponse  createBucket  (CreateBucketRequest req);
    DeleteBucketResponse  deleteBucket  (DeleteBucketRequest req);
    ListBucketsResponse   listBuckets   (String authPrincipal);
}

/** HTTP front-end that maps requests to the S3Gateway. */
public final class S3GatewayServer {
    public static S3GatewayServer start(int port, S3Gateway gateway);
    public int port();
    public void stop();
}
```

The HTTP server may use `com.sun.net.httpserver.HttpServer` (JDK built-in) for the first pass. Netty / Jetty migration is a future improvement.

---

## 4. Data model

### 4.1 Bucket record

```java
public record Bucket(
    UUID    bucketId,
    UUID    tenantId,
    String  name,                          // S3 bucket name (DNS-compatible)
    long    inodeRoot,                     // root inode for objects in this bucket
    Versioning versioning,
    Optional<EncryptionPolicy> encryption,
    Optional<LifecyclePolicy>  lifecycle,
    String  regionPolicy,                  // e.g. "us-east-1" or "any"
    Instant createdAt
) {}

public enum Versioning { ENABLED, SUSPENDED, DISABLED }
public record EncryptionPolicy(String kmsKeyId, String algorithm) {}
public record LifecyclePolicy(byte[] rules) {}
```

### 4.2 Multipart upload record

```java
public record MultipartUpload(
    String  uploadId,
    UUID    bucketId,
    String  key,
    List<MultipartPart> parts,
    Instant initiatedAt,
    Instant expiresAt                      // default: initiatedAt + 7 days
) {}

public record MultipartPart(
    int    partNumber,    // 1-based, S3 convention
    String etag,          // MD5 of the part bytes, hex-encoded
    long   size,
    long   inodeId        // staging-file inode under /_multipart/{bucketId}/{uploadId}/
) {}
```

### 4.3 Request / response records

For each `S3Gateway` method, define a paired request and response record. Pattern:

```java
public record PutObjectRequest(
    String authPrincipal,    // resolved by the HTTP layer from SigV4
    String bucket,
    String key,
    InputStream body,
    long   contentLength,
    Optional<String> contentType
) {}

public record PutObjectResponse(
    String etag,             // MD5 of bytes, hex-encoded
    long   size,
    Instant lastModified
) {}
```

Define the rest analogously. Keep all request/response records **immutable** and use `record` not `class`.

### 4.4 Storage backing for gateway state

Bucket records live as files in `/{cluster}/_buckets/{bucketName}.json` via the client library. Multipart records live in `/{cluster}/_multipart/{bucketId}/{uploadId}/manifest.json`. The gateway does not touch the MDS or OSDs directly — every read or write of gateway-owned state goes through the client library.

---

## 5. Life of a request

### 5.1 Single PUT

```
HTTP client → S3GatewayServer → S3Gateway.putObject → ClientLibrary
```

1. HTTP layer receives `PUT /mybucket/myfile.bin` with body.
2. HTTP layer parses the SigV4 signature, derives `authPrincipal`. If signature invalid → `403 Forbidden`.
3. HTTP layer calls `S3Gateway.putObject(req)`.
4. Gateway looks up bucket record by name (cache; cache miss → read from `/_buckets/mybucket.json`). If not found → `404 NoSuchBucket`.
5. Gateway verifies `authPrincipal` has write permission on the bucket. (Per ACL, or per static IAM map in the stubbed implementation.) If not → `403 AccessDenied`.
6. Gateway opens destination file via client library: `client.create("/{bucket.inodeRoot}/myfile.bin")`.
7. Gateway streams request body through the client library write API. The client library handles scatter-gather to OSDs.
8. Gateway closes the file; client library fsyncs.
9. Gateway computes ETag = MD5 of the request body bytes (hex, lowercase, no quotes in the field but quoted on the wire).
10. Gateway returns `PutObjectResponse(etag, size, lastModified)`.
11. HTTP layer translates to `200 OK` with header `ETag: "..."`.

### 5.2 Multipart PUT (5 GB object, 1000 × 5 MB parts)

1. Client `POST /mybucket/myfile.bin?uploads`. Gateway authenticates; mints `uploadId`; writes initial `MultipartUpload` record (parts list empty) to `/_multipart/{bucketId}/{uploadId}/manifest.json`. Returns `<InitiateMultipartUploadResult>{uploadId}</...>`.
2. For each part N in 1..1000: client `PUT /mybucket/myfile.bin?partNumber=N&uploadId={uid}` with the part bytes. Gateway opens a staging file `/_multipart/{bucketId}/{uploadId}/part-{N}` via client library; streams bytes; closes; computes ETag; appends a `MultipartPart` entry to the manifest. Returns `200` with `ETag: "..."`.
3. Client `POST /mybucket/myfile.bin?uploadId={uid}` with the ordered list of part ETags in the request body. Gateway:
   a. Validates the ETag list against the manifest (each part exists, ETags match).
   b. Opens the destination file via client library: `client.create("/{bucket.inodeRoot}/myfile.bin")`.
   c. Concatenates the part inodes — either by copying (simple, slow — this is the only supported mode for the initial pass), or via a server-side splice op (fast, complex — out of scope).
   d. Closes the destination file.
   e. Deletes the staging directory `/_multipart/{bucketId}/{uploadId}/`.
   f. Computes the multipart ETag = MD5 of (concatenated part ETag bytes) + `-{partCount}`. (S3 convention.)
4. Returns `<CompleteMultipartUploadResult>{etag}</...>` and `200`.

### 5.3 GET

1. HTTP layer routes to `S3Gateway.getObject(req)`.
2. Gateway authenticates, resolves bucket. Returns `404 NoSuchBucket` / `404 NoSuchKey` as appropriate.
3. Gateway opens the file via client library, streams bytes back to HTTP layer. HTTP layer sets `Content-Length`, `ETag`, `Last-Modified`, `Content-Type` headers and streams the body.

### 5.4 LIST with pagination

1. HTTP layer routes to `S3Gateway.listObjects(req)`.
2. Gateway calls `client.readdir("/{bucket.inodeRoot}")` to enumerate entries. **Apply prefix filter** at the gateway: skip entries whose name doesn't start with `prefix`.
3. **Continuation token** is an opaque server-side cursor — base64-encoded byte representation of the last key returned in the previous page. The server resumes the readdir from immediately after that key. (For the initial pass: read the whole directory and slice in-memory; switch to a true cursor when `readdir` supports it.)
4. Cap the page at `max-keys` (default and max: 1000).
5. Return `<ListBucketResult>` with entries and, if truncated, a `<NextContinuationToken>`.

---

## 6. Invariants the implementation must hold

After a successful `PutObject(bucket, key, body)`:
- The object is durable: the underlying client-library write has been acked. A subsequent `GetObject(bucket, key)` on a healthy cluster returns exactly the bytes that were PUT.
- ETag equals MD5 of the body (hex, lowercase).
- The object appears in `ListObjects(bucket)` results.

After a successful `CompleteMultipart`:
- The object is durable.
- Subsequent `GetObject` returns the concatenation of the part bodies, in part-number order.
- The multipart record and staging files are removed.

After `AbortMultipart`:
- All part staging inodes are unlinked.
- The multipart record is removed.
- No partial object is visible via `GetObject` or `ListObjects`.

After `DeleteObject`:
- Subsequent `GetObject` returns `404 NoSuchKey`.
- The object does not appear in `ListObjects` results.

The gateway must **never return `500 InternalError`** for an expected failure mode. Every documented failure routes to one of the listed S3 error codes (see §7).

---

## 7. Failure modes & required handling

| Trigger | HTTP response | S3 error code | Notes |
|---|---|---|---|
| Signature invalid or missing | 403 | `SignatureDoesNotMatch` | also covers expired signatures |
| Bucket does not exist | 404 | `NoSuchBucket` | |
| Bucket exists, key does not (GET/HEAD/DELETE) | 404 | `NoSuchKey` | DELETE is idempotent — 204 on second delete |
| Bucket name malformed | 400 | `InvalidBucketName` | DNS-compliant only |
| Bucket not empty (on DELETE bucket) | 409 | `BucketNotEmpty` | |
| Bucket already exists | 409 | `BucketAlreadyExists` | |
| Multipart `uploadId` not found | 404 | `NoSuchUpload` | |
| Part ETag list invalid on complete | 400 | `InvalidPart` or `InvalidPartOrder` | |
| Continuation token malformed | 400 | `InvalidArgument` | |
| Client library throws on a read/write | 503 | `ServiceUnavailable` | log + increment failure metric |
| Body size exceeds bucket / tenant quota | 413 | `EntityTooLarge` | (quota check optional in first pass) |
| Auth principal lacks permission | 403 | `AccessDenied` | |

Abandoned multipart uploads past their `expiresAt` are cleaned up by the Custodian (§5) on a periodic scan — the gateway only writes the expiry; it does not run the GC.

---

## 8. Testing acceptance criteria

Required tests in `dfs-gateway-s3/src/test/java/com/hkg/dfs/gateway/s3/`. Use the in-memory client-library substrate (mock or `dfs-simulator`-backed) so tests don't touch disk.

| Test class | Test method | Asserts |
|---|---|---|
| `S3GatewayPutGetTest` | `singlePartRoundTrip` | PUT 1 MiB → GET → bytes identical |
| `S3GatewayPutGetTest` | `etagIsMd5OfBody` | ETag header equals MD5 of body, hex lowercase, length 32 |
| `S3GatewayPutGetTest` | `getOnMissingKeyReturns404` | GET unknown key → 404 NoSuchKey |
| `S3GatewayPutGetTest` | `headReturnsSameMetadataAsGet` | HEAD and GET return identical headers (size, etag, last-modified) |
| `S3GatewayDeleteTest` | `deleteRemovesObject` | DELETE → GET → 404 |
| `S3GatewayDeleteTest` | `deleteIsIdempotent` | DELETE existing → 204; second DELETE → 204 |
| `S3GatewayMultipartTest` | `multipartRoundTrip` | Init, upload 5 × 1 MiB parts, complete, GET, bytes match concatenation |
| `S3GatewayMultipartTest` | `multipartEtagFormat` | Complete returns ETag of form `<md5>-<partCount>` |
| `S3GatewayMultipartTest` | `multipartAbortRemovesParts` | After abort, staging directory empty; object not visible |
| `S3GatewayMultipartTest` | `completeWithMissingPartFails` | Complete with a part ETag not in the manifest → 400 InvalidPart |
| `S3GatewayAuthTest` | `invalidSignatureReturns403` | Bad SigV4 → 403 SignatureDoesNotMatch |
| `S3GatewayAuthTest` | `unauthorizedPrincipalReturns403` | Valid signature, no permission on bucket → 403 AccessDenied |
| `S3GatewayBucketTest` | `createListDeleteBucket` | Create bucket, list contains it, delete, list does not contain it |
| `S3GatewayBucketTest` | `deleteNonEmptyBucketReturns409` | Delete bucket with at least one key → 409 BucketNotEmpty |
| `S3GatewayListTest` | `listEmptyBucket` | Empty bucket → 200 with no `Contents` entries |
| `S3GatewayListTest` | `listWithPrefixFilter` | Returns only keys with the given prefix |
| `S3GatewayListTest` | `paginationWorks` | Bucket with 1500 keys, max-keys=1000 → first page truncated with continuation token; second page returns remaining 500 |

All tests pass under `./gradlew :dfs-gateway-s3:test`.

---

## 9. Stubs allowed / out of scope (initial pass)

- **Auth backend.** Use a static in-memory `Map<String, String>` of `{accessKey → secretKey}` for SigV4 verification. Real IAM / STS / role assumption is out of scope.
- **Bucket ACL.** Single owner per bucket (the principal that created it). Bucket policies, ACLs, cross-account access — out of scope.
- **KMS.** Encryption-at-rest is acknowledged but stubbed. Real KMS integration lands once `dfs-security` exposes a KMS interface.
- **Versioning.** Bucket `Versioning` flag is recorded but versioned reads/writes are not implemented — all PUTs overwrite.
- **Lifecycle policies.** Records stored but no enforcement.
- **Cross-region replication.** Gateway respects `bucket.regionPolicy` to refuse out-of-region operations but does not initiate replication — that's §9.
- **Server-side concatenate.** Multipart complete uses copy-mode only (read each staging part, write to destination). Splice-mode (atomically reassigning extent lists) is out of scope.
- **HTTPS / TLS.** First-pass server is HTTP only. TLS termination is delegated to an upstream load balancer in production.

---

## 10. Dependencies

### Build

```groovy
// In root build.gradle, add:
project(':dfs-gateway-s3') {
    dependencies {
        api project(':dfs-common')
        api project(':dfs-client')            // §6 — see "Mock client library" below
        implementation project(':dfs-security')   // optional for stub mode

        implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'
        implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.0'
    }
}
```

### Runtime

- JDK 17+.
- Jackson for XML/JSON serialization.
- `com.sun.net.httpserver.HttpServer` from the JDK (no external HTTP framework for the initial pass).

### Mock client library (since §6 doesn't exist yet)

This SPEC assumes a `ClientLibrary` interface in `dfs-client`. Until §6 lands, implement an in-memory mock that supports the subset of operations the gateway needs:

```java
package com.hkg.dfs.client;     // or wherever §6 will live

public interface ClientLibrary {
    long create(String path);               // returns inode id
    long open(String path);                 // returns inode id; throws FileNotFoundException
    void write(long inode, long offset, byte[] bytes);
    int  read (long inode, long offset, byte[] buf);
    long size (long inode);
    void close(long inode);                 // fsyncs
    void unlink(String path);
    void mkdir(String path);
    List<String> readdir(String path);
    boolean exists(String path);
}
```

Implement `InMemoryClientLibrary implements ClientLibrary` in this module's test source set. When §6 lands, the gateway depends on the real one; the mock stays in tests.

### Wiki concepts implemented

- [`multipart-upload`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/multipart-upload.md)
- [`multipart-etag`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/multipart-etag.md)

### Essay section

[§7. Object Gateway (S3 REST / Swift / RGW-class)](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-file-system-full.md#7-object-gateway-s3-rest--swift--rgw-class)

---

## 11. Implementation checklist

Roughly in order. Each item should land with its tests.

**Foundations**
- [ ] `S3GatewayConfig` (port, auth-backend, bucket-root-inode)
- [ ] `Bucket`, `MultipartUpload`, `MultipartPart` records
- [ ] All request/response record pairs (`PutObjectRequest`/`PutObjectResponse`, etc.)
- [ ] `S3Gateway` interface
- [ ] Mock `InMemoryClientLibrary` in `src/test/java/.../testutil/`

**HTTP layer**
- [ ] `S3GatewayServer` with `HttpServer`; URL routing dispatch table
- [ ] SigV4 verifier + `AuthContext`
- [ ] S3 XML response writers (success + error envelopes)
- [ ] Request body streaming (no buffering of full body for large PUTs)

**Bucket ops**
- [ ] `createBucket` + `S3GatewayBucketTest.createListDeleteBucket`
- [ ] `listBuckets`
- [ ] `deleteBucket` + `S3GatewayBucketTest.deleteNonEmptyBucketReturns409`

**Object ops**
- [ ] `putObject` (single-part) + `S3GatewayPutGetTest.singlePartRoundTrip` + `etagIsMd5OfBody`
- [ ] `getObject` + tests
- [ ] `headObject` + test
- [ ] `deleteObject` + `S3GatewayDeleteTest.deleteRemovesObject` + `deleteIsIdempotent`
- [ ] `listObjects` with prefix + pagination + `S3GatewayListTest.paginationWorks`

**Multipart ops**
- [ ] `createMultipart`, `uploadPart`, `completeMultipart`, `abortMultipart` + four `S3GatewayMultipartTest.*`

**Wiring**
- [ ] Add `':dfs-gateway-s3'` to `settings.gradle`
- [ ] Add dependency block to root `build.gradle`
- [ ] All tests pass under `./gradlew :dfs-gateway-s3:test`

**Documentation**
- [ ] Write `docs/modules/dfs-gateway-s3.md` in the existing 7-section format (Role → Wiki anchor → Public API surface → Internal structure → Key tests → Where it fits → Stubs and departures from production)
- [ ] Add `dfs-gateway-s3` row to `docs/modules/README.md` index
- [ ] Update [`wiki/my-explanations/distributed-file-system-services.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-file-system-services.md) §7 status from ❌ Missing → ✅ Implemented

When all boxes are ticked, this SPEC.md can be moved to `docs/specs/dfs-gateway-s3.md` for historical reference. The `docs/modules/dfs-gateway-s3.md` doc becomes the contract going forward.
