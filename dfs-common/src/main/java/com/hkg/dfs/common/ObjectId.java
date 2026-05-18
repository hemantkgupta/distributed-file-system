package com.hkg.dfs.common;

import java.util.Objects;

/**
 * A globally-unique opaque object identifier.
 * Implements identity types described in {@code wiki/my-explanations/design-distributed-file-system}.
 */
public record ObjectId(String value) {
    public ObjectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ObjectId cannot be blank");
        }
    }

    public static ObjectId of(String value) {
        return new ObjectId(value);
    }
}
