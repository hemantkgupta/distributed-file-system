package com.hkg.dfs.qos;

/** A traffic class with reservation/weight/limit parameters. */
public record QosClass(String name, double reservation, double weight, double limit) {
    public QosClass {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        if (reservation < 0 || weight <= 0 || limit < reservation) {
            throw new IllegalArgumentException("invalid qos params");
        }
    }
}
