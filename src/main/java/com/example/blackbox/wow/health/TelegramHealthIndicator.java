package com.example.blackbox.wow.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component("telegramApiHealthIndicator")
public class TelegramHealthIndicator implements HealthIndicator {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final long SLOW_CHECK_WARNING_MILLIS = 2_000;

    private final TelegramClient telegramClient;
    private final AtomicReference<CachedHealth> cachedHealth = new AtomicReference<>();

    public TelegramHealthIndicator(@Qualifier("telegramHealthClient") TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public synchronized Health health() {
        Instant now = Instant.now();
        CachedHealth cached = cachedHealth.get();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.up() ? Health.up().build() : Health.down().build();
        }

        long startedAt = System.nanoTime();
        boolean up = false;
        try {
            var bot = telegramClient.execute(GetMe.builder().build());
            up = bot != null && bot.getIsBot();
            if (!up) {
                log.warn("Telegram health check returned an invalid bot response");
            }
        } catch (Exception e) {
            log.warn(
                    "Telegram health check failed after {} ms ({})",
                    elapsedMillis(startedAt),
                    rootCauseType(e)
            );
        }

        long elapsedMillis = elapsedMillis(startedAt);
        if (up && elapsedMillis >= SLOW_CHECK_WARNING_MILLIS) {
            log.warn("Telegram health check succeeded slowly after {} ms", elapsedMillis);
        }

        cachedHealth.set(new CachedHealth(up, now.plus(CACHE_TTL)));
        return up ? Health.up().build() : Health.down().build();
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String rootCauseType(Throwable failure) {
        Throwable rootCause = failure;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getClass().getSimpleName();
    }

    private record CachedHealth(boolean up, Instant expiresAt) {
    }
}
