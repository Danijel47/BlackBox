package com.example.blackbox.time_to_go.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "time-to-go")
public record TimeToGoProperties(
        TomTom tomtom,
        History history,
        Sampling sampling
) {

    public record TomTom(String apiKey) {
    }

    public record History(String path) {
    }

    public record Sampling(boolean enabled, String cron) {
    }

}
