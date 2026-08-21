package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wow.token-history")
public record WowTokenHistoryProperties(boolean enabled, boolean backfillEnabled) {
}
