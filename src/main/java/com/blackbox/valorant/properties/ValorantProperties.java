package com.blackbox.valorant.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "valorant")
public record ValorantProperties(String apiKey, Duration refreshInterval) {
    public ValorantProperties {
        refreshInterval = refreshInterval == null ? Duration.ofHours(1) : refreshInterval;
        if (refreshInterval.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("Valorant refresh interval must be at least one minute.");
        }
    }
}
