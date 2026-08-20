package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.Objects;

@ConfigurationProperties("telegram.daily-prompt")
public record TelegramDailyPromptProperties(
        boolean enabled,
        long targetUserId,
        long chatId,
        String message,
        ZoneId zone
) {
    public TelegramDailyPromptProperties {
        Objects.requireNonNull(message, "message must be configured");
        Objects.requireNonNull(zone, "zone must be configured");
        if (enabled && targetUserId <= 0) {
            throw new IllegalArgumentException("targetUserId must be positive when the daily prompt is enabled");
        }
        if (enabled && message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank when the daily prompt is enabled");
        }
    }
}
