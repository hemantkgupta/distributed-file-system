package com.hkg.dfs.security;

/** Thrown when decrypt is attempted on a destroyed DUK. */
public final class KeyDestroyedException extends RuntimeException {
    public KeyDestroyedException(DukId id) {
        super("key destroyed for " + id);
    }
}
