package com.blackbox.time_to_go.service;

import com.blackbox.time_to_go.model.TravelRoute;
import com.blackbox.time_to_go.properties.TimeToGoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TimeToGoSampler {

    private final TimeToGoProperties properties;
    private final TimeToGoService timeToGo;

    public TimeToGoSampler(TimeToGoProperties properties, TimeToGoService timeToGo) {
        this.properties = properties;
        this.timeToGo = timeToGo;
    }

    @Scheduled(cron = "${time-to-go.sampling.cron:0 0,30 * * * *}", zone = "Europe/Zagreb")
    public void sampleConfiguredRoutes() {
        if (properties.sampling() == null || !properties.sampling().enabled() || !timeToGo.isConfigured()) {
            return;
        }

        for (TravelRoute route : TravelRoute.values()) {
            try {
                timeToGo.sampleNow(route);
            } catch (Exception e) {
                log.warn("Time to go sample failed for {}: {}", route.key(), e.getMessage());
            }
        }
    }
}
