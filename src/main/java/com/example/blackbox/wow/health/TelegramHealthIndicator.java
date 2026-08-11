package com.example.blackbox.wow.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.Instant;

@Component
public class TelegramHealthIndicator implements HealthIndicator {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final TelegramClient telegramClient;
    private volatile CachedHealth cachedHealth;

    public TelegramHealthIndicator(@Qualifier("rioClient") TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public synchronized Health health() {
        Instant now = Instant.now();
        CachedHealth cached = cachedHealth;
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.up() ? Health.up().build() : Health.down().build();
        }

        boolean up;
        try {
            var bot = telegramClient.execute(GetMe.builder().build());
            up = bot != null && Boolean.TRUE.equals(bot.getIsBot());
        } catch (Exception e) {
            up = false;
        }

        cachedHealth = new CachedHealth(up, now.plus(CACHE_TTL));
        return up ? Health.up().build() : Health.down().build();
    }

    private record CachedHealth(boolean up, Instant expiresAt) {
    }
}
