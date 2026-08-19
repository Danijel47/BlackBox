package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wow.mplus-performance")
public record MPlusPerformanceProperties(int minimumSampleSize) {
    public MPlusPerformanceProperties {
        if (minimumSampleSize < 1 || minimumSampleSize > 100) {
            throw new IllegalArgumentException("minimumSampleSize must be between 1 and 100");
        }
    }
}
