package com.blackbox.time_to_go.entity;

import com.blackbox.time_to_go.model.TravelTimeSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "time_to_go_snapshot")
public class TimeToGoSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_key", nullable = false, length = 64)
    private String routeKey;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(name = "departure_time", nullable = false)
    private Instant departureTime;

    @Column(name = "distance_meters", nullable = false)
    private int distanceMeters;

    @Column(name = "travel_time_seconds", nullable = false)
    private int travelTimeSeconds;

    @Column(name = "no_traffic_travel_time_seconds", nullable = false)
    private int noTrafficTravelTimeSeconds;

    @Column(name = "historic_traffic_travel_time_seconds", nullable = false)
    private int historicTrafficTravelTimeSeconds;

    @Column(name = "live_traffic_incidents_travel_time_seconds", nullable = false)
    private int liveTrafficIncidentsTravelTimeSeconds;

    @Column(name = "traffic_delay_seconds", nullable = false)
    private int trafficDelaySeconds;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    protected TimeToGoSnapshotEntity() {
    }

    public TimeToGoSnapshotEntity(TravelTimeSnapshot snapshot) {
        this.routeKey = snapshot.routeKey();
        this.sampledAt = snapshot.sampledAt();
        this.departureTime = snapshot.departureTime();
        this.distanceMeters = snapshot.distanceMeters();
        this.travelTimeSeconds = snapshot.travelTimeSeconds();
        this.noTrafficTravelTimeSeconds = snapshot.noTrafficTravelTimeSeconds();
        this.historicTrafficTravelTimeSeconds = snapshot.historicTrafficTravelTimeSeconds();
        this.liveTrafficIncidentsTravelTimeSeconds = snapshot.liveTrafficIncidentsTravelTimeSeconds();
        this.trafficDelaySeconds = snapshot.trafficDelaySeconds();
        this.provider = snapshot.provider();
    }

    public TravelTimeSnapshot toSnapshot() {
        return new TravelTimeSnapshot(
                routeKey,
                sampledAt,
                departureTime,
                distanceMeters,
                travelTimeSeconds,
                noTrafficTravelTimeSeconds,
                historicTrafficTravelTimeSeconds,
                liveTrafficIncidentsTravelTimeSeconds,
                trafficDelaySeconds,
                provider
        );
    }
}
