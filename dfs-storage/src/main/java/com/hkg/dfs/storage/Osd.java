package com.hkg.dfs.storage;

import com.hkg.dfs.common.ObjectId;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.zip.CRC32C;

/**
 * BlueStore-style Object Storage Device. Two write paths:
 * <ul>
 *   <li>Large writes: copy-on-write into a fresh extent + metadata commit.</li>
 *   <li>Small writes: in-memory WAL queue; background flush merges into an extent.</li>
 * </ul>
 * A {@link ConcurrentSkipListMap} stands in for the RocksDB metadata. Every
 * stored block carries a CRC32c and is verified on read.
 */
public final class Osd {
    private static final int SMALL_WRITE_THRESHOLD = 4096;

    private final ConcurrentSkipListMap<String, byte[]> data = new ConcurrentSkipListMap<>();
    private final Map<String, Integer> crc = new HashMap<>();
    private final Deque<WalEntry> wal = new ArrayDeque<>();
    private final Map<ObjectId, Location> objectLocations = new HashMap<>();

    public synchronized void writeLarge(String extentId, long offset, byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        String key = key(extentId, offset);
        data.put(key, copy);
        crc.put(key, crc32c(copy));
    }

    public synchronized void writeSmall(ObjectId obj, byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        if (bytes.length > SMALL_WRITE_THRESHOLD) {
            throw new IllegalArgumentException("use writeLarge for >" + SMALL_WRITE_THRESHOLD + "B");
        }
        wal.add(new WalEntry(obj, Arrays.copyOf(bytes, bytes.length)));
    }

    public synchronized Map<ObjectId, Location> flushDeferred(String extentId, long baseOffset) {
        Map<ObjectId, Location> flushed = new HashMap<>();
        long off = baseOffset;
        while (!wal.isEmpty()) {
            WalEntry e = wal.poll();
            writeLarge(extentId, off, e.bytes);
            Location loc = new Location(extentId, off, e.bytes.length);
            objectLocations.put(e.obj, loc);
            flushed.put(e.obj, loc);
            off += e.bytes.length;
        }
        return flushed;
    }

    public synchronized Optional<Location> lookup(ObjectId obj) {
        return Optional.ofNullable(objectLocations.get(obj));
    }

    public synchronized byte[] read(String extentId, long offset, int length) {
        String key = key(extentId, offset);
        byte[] blob = data.get(key);
        if (blob == null) throw new IllegalStateException("not found: " + key);
        int expected = crc.getOrDefault(key, 0);
        int actual = crc32c(blob);
        if (expected != actual) {
            throw new ChecksumMismatchException(key, expected, actual);
        }
        int n = Math.min(length, blob.length);
        return Arrays.copyOfRange(blob, 0, n);
    }

    public synchronized void corruptForTest(String extentId, long offset) {
        String key = key(extentId, offset);
        byte[] blob = data.get(key);
        if (blob == null) return;
        blob[0] = (byte) (blob[0] ^ 0xFF);
    }

    public synchronized int walSize() { return wal.size(); }

    private static String key(String extentId, long offset) {
        return extentId + "@" + offset;
    }

    private static int crc32c(byte[] b) {
        CRC32C c = new CRC32C();
        c.update(b);
        return (int) c.getValue();
    }

    private record WalEntry(ObjectId obj, byte[] bytes) {}
    public record Location(String extentId, long offset, int length) {}
}
