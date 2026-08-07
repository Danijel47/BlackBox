package com.example.blackbox.time_to_go.model;

import java.time.Instant;

public record TravelTimeSnapshot(
        String routeKey,
        Instant sampledAt,
        Instant departureTime,
        int distanceMeters,
        int travelTimeSeconds,
        int noTrafficTravelTimeSeconds,
        int historicTrafficTravelTimeSeconds,
        int liveTrafficIncidentsTravelTimeSeconds,
        int trafficDelaySeconds,
        String provider
) {
}
