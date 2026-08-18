package com.blackbox.wow.blizzard;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public class BlizzardCache<T> {

    private final Cache<String, Entry<T>> entries;
    private final ToIntFunction<T> valueWeigher;

    public BlizzardCache(long maximumWeight, ToIntFunction<T> valueWeigher) {
        this.valueWeigher = valueWeigher;
        Weigher<String, Entry<T>> entryWeigher = (key, entry) -> entry.weight();
        this.entries = Caffeine.newBuilder()
                .maximumWeight(Math.max(1, maximumWeight))
                .weigher(entryWeigher)
                .expireAfterAccess(Duration.ofHours(24))
                .build();
    }

    public synchronized T getOrCompute(String key, Duration ttl, Supplier<T> supplier) {
        Entry<T> existing = entries.getIfPresent(key);
        if (existing != null && !existing.isExpired()) {
            return existing.value();
        }
        if (existing != null) {
            entries.invalidate(key);
        }

        T value = supplier.get();
        if (value != null) {
            int weight = Math.max(1, valueWeigher.applyAsInt(value));
            entries.put(key, new Entry<>(value, Instant.now().plus(ttl), weight));
        }
        return value;
    }

    long estimatedSize() {
        return entries.estimatedSize();
    }

    private record Entry<T>(T value, Instant expiresAt, int weight) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
