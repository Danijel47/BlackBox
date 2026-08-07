package com.example.blackbox.wow.blizzard;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class BlizzardCache<T> {
    private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();

    public T getOrCompute(String key, Duration ttl, Supplier<T> supplier) {
        Entry<T> existing = entries.get(key);
        if (existing != null && !existing.isExpired()) {
            return existing.value();
        }

        T value = supplier.get();
        if (value != null) {
            entries.put(key, new Entry<>(value, Instant.now().plus(ttl)));
        }
        return value;
    }

    private record Entry<T>(T value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
