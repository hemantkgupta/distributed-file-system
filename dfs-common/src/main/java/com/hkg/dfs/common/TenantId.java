package com.hkg.dfs.common;

import java.util.Objects;

/** Tenant boundary identifier used by ACL and crypto-shredding paths. */
public record TenantId(String value) {
    public TenantId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TenantId cannot be blank");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }
}
