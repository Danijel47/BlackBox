package com.blackbox.time_to_go.service;

import com.blackbox.time_to_go.client.TomTomRoutingClient;
import com.blackbox.time_to_go.entity.TimeToGoSnapshotEntity;
import com.blackbox.time_to_go.model.TravelRoute;
import com.blackbox.time_to_go.model.TravelTimeSnapshot;
import com.blackbox.time_to_go.repository.TimeToGoSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TimeToGoService {

    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    private final TomTomRoutingClient tomTom;
    private final TimeToGoSnapshotRepository snapshots;

    public TimeToGoService(TomTomRoutingClient tomTom, TimeToGoSnapshotRepository snapshots) {
        this.tomTom = tomTom;
        this.snapshots = snapshots;
    }

    public boolean isConfigured() {
        return tomTom.isConfigured();
    }

    public TravelTimeSnapshot sampleNow(TravelRoute route) {
        TravelTimeSnapshot snapshot = tomTom.calculateRoute(route, Instant.now());
        snapshots.save(new TimeToGoSnapshotEntity(snapshot));
        return snapshot;
    }

    public Optional<CombinedTravelTime> getCombinedLatest(TravelRoute route) {
        Optional<TravelTimeSnapshot> latest = snapshots.findFirstByRouteKeyOrderBySampledAtDesc(route.key())
                .map(TimeToGoSnapshotEntity::toSnapshot);
        if (latest.isEmpty()) {
            return Optional.empty();
        }

        TravelTimeSnapshot current = latest.get();
        var local = current.departureTime().atZone(ZAGREB);
        HistoricalAverage matchingAverage = averageFor(route, local.getDayOfWeek(), local.getHour());
        return Optional.of(new CombinedTravelTime(route, current, matchingAverage));
    }

    public List<HistoricalAverage> bestHistoricalSlots(TravelRoute route, int limit) {
        return findRouteHistory(route).stream()
                .collect(Collectors.groupingBy(snapshot -> slotKey(snapshot.departureTime())))
                .entrySet().stream()
                .map(entry -> toAverage(route, entry.getKey(), entry.getValue()))
                .filter(avg -> avg.samples() >= 1)
                .sorted(Comparator.comparingInt(HistoricalAverage::averageTravelTimeSeconds))
                .limit(limit)
                .toList();
    }

    private HistoricalAverage averageFor(TravelRoute route, DayOfWeek day, int hour) {
        String key = day.name() + "|" + hour;
        List<TravelTimeSnapshot> samples = findRouteHistory(route).stream()
                .filter(snapshot -> slotKey(snapshot.departureTime()).equals(key))
                .toList();
        if (samples.isEmpty()) {
            return null;
        }
        return toAverage(route, key, samples);
    }

    private List<TravelTimeSnapshot> findRouteHistory(TravelRoute route) {
        return snapshots.findByRouteKey(route.key()).stream()
                .map(TimeToGoSnapshotEntity::toSnapshot)
                .toList();
    }

    private static HistoricalAverage toAverage(TravelRoute route, String slotKey, List<TravelTimeSnapshot> snapshots) {
        String[] parts = slotKey.split("\\|");
        int avg = (int) Math.round(snapshots.stream()
                .mapToInt(TravelTimeSnapshot::travelTimeSeconds)
                .average()
                .orElse(0));
        return new HistoricalAverage(route, DayOfWeek.valueOf(parts[0]), Integer.parseInt(parts[1]), avg, snapshots.size());
    }

    private static String slotKey(Instant instant) {
        var local = instant.atZone(ZAGREB);
        return local.getDayOfWeek().name() + "|" + local.getHour();
    }

    public record CombinedTravelTime(
            TravelRoute route,
            TravelTimeSnapshot current,
            HistoricalAverage matchingAverage
    ) {
    }

    public record HistoricalAverage(
            TravelRoute route,
            DayOfWeek day,
            int hour,
            int averageTravelTimeSeconds,
            int samples
    ) {
    }
}
