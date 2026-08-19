package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wow.mplus-collection")
public record MPlusCollectionProperties(
        boolean enabled,
        int maxDetailRequestsPerProfile,
        int retryMaxDelaySeconds
) {
    public MPlusCollectionProperties {
        if (maxDetailRequestsPerProfile < 0) {
            throw new IllegalArgumentException("maxDetailRequestsPerProfile must not be negative");
        }
        if (retryMaxDelaySeconds < 0 || retryMaxDelaySeconds > 30) {
            throw new IllegalArgumentException("retryMaxDelaySeconds must be between 0 and 30");
        }
    }
}
