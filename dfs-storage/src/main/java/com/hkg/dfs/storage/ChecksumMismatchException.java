package com.hkg.dfs.storage;

/** Thrown when an OSD read detects a CRC32c mismatch. */
public final class ChecksumMismatchException extends RuntimeException {
    private final int expected;
    private final int actual;

    public ChecksumMismatchException(String key, int expected, int actual) {
        super("crc mismatch at " + key + ": expected=" + expected + " actual=" + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public int expected() { return expected; }
    public int actual() { return actual; }
}
