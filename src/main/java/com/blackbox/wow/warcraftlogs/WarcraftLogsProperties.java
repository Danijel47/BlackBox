package com.blackbox.wow.warcraftlogs;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;

@ConfigurationProperties(prefix = "warcraft-logs")
public record WarcraftLogsProperties(
        String clientId,
        String clientSecret,
        String tokenUrl,
        String apiUrl,
        int recentReportLimit,
        boolean collectionEnabled,
        String seasonKey,
        Instant seasonStart,
        int rateLimitMaxPercent
) {
}
