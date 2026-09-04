package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wow.housing-market")
public record HousingMarketProperties(
        int resultLimit,
        int tsmCacheHours,
        int catalogCacheHours
) {
    private static final int MAXIMUM_RESULT_LIMIT = 20;
    private static final int MAXIMUM_CACHE_HOURS = 168;

    public HousingMarketProperties {
        if (resultLimit <= 0 || resultLimit > MAXIMUM_RESULT_LIMIT) {
            throw new IllegalArgumentException("Housing result limit must be between 1 and 20.");
        }
        validateCacheHours(tsmCacheHours, "TSM");
        validateCacheHours(catalogCacheHours, "catalog");
    }

    private static void validateCacheHours(int cacheHours, String label) {
        if (cacheHours <= 0 || cacheHours > MAXIMUM_CACHE_HOURS) {
            throw new IllegalArgumentException(label + " cache hours must be between 1 and 168.");
        }
    }
}
