package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

@ConfigurationProperties(prefix = "wow.tuning-news")
public record WowTuningNewsProperties(
        boolean enabled,
        long chatId,
        URI feedUrl,
        Duration initialLookback,
        int maxItemsPerRun
) {
    public WowTuningNewsProperties {
        if (feedUrl == null || !"https".equalsIgnoreCase(feedUrl.getScheme())) {
            throw new IllegalArgumentException("wow.tuning-news.feed-url must be an HTTPS URL");
        }
        if (!isWowheadHost(feedUrl.getHost())) {
            throw new IllegalArgumentException("wow.tuning-news.feed-url must use the wowhead.com domain");
        }
        if (initialLookback == null || initialLookback.isNegative() || initialLookback.isZero()) {
            throw new IllegalArgumentException("wow.tuning-news.initial-lookback must be positive");
        }
        if (maxItemsPerRun < 1 || maxItemsPerRun > 10) {
            throw new IllegalArgumentException("wow.tuning-news.max-items-per-run must be between 1 and 10");
        }
    }

    private static boolean isWowheadHost(String host) {
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals("wowhead.com") || normalizedHost.endsWith(".wowhead.com");
    }
}
