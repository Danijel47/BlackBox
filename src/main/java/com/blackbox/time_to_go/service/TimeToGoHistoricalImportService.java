package com.blackbox.time_to_go.service;

import com.blackbox.time_to_go.client.TomTomTrafficStatsClient;
import com.blackbox.time_to_go.entity.TimeToGoImportJobEntity;
import com.blackbox.time_to_go.entity.TimeToGoSnapshotEntity;
import com.blackbox.time_to_go.model.TravelTimeSnapshot;
import com.blackbox.time_to_go.repository.TimeToGoImportJobRepository;
import com.blackbox.time_to_go.repository.TimeToGoSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TimeToGoHistoricalImportService {

    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    private final TomTomTrafficStatsClient trafficStats;
    private final TimeToGoImportJobRepository importJobs;
    private final TimeToGoSnapshotRepository snapshots;

    public TimeToGoHistoricalImportService(
            TomTomTrafficStatsClient trafficStats,
            TimeToGoImportJobRepository importJobs,
            TimeToGoSnapshotRepository snapshots
    ) {
        this.trafficStats = trafficStats;
        this.importJobs = importJobs;
        this.snapshots = snapshots;
    }

    public String submitLast30DaysImport() {
        LocalDate to = LocalDate.now(ZAGREB).minusDays(1);
        LocalDate from = to.minusDays(29);
        String importKey = "time-to-go-30d-" + UUID.randomUUID();
        LocalDate firstTo = from.plusDays(14);
        LocalDate secondFrom = firstTo.plusDays(1);
        Instant now = Instant.now();

        String firstJobId = trafficStats.submitHistoricalJob(from, firstTo, importKey + "-1");
        importJobs.save(new TimeToGoImportJobEntity(importKey, firstJobId, "SUBMITTED", from, firstTo, now, "Submitted to TomTom Traffic Stats"));

        String secondJobId = trafficStats.submitHistoricalJob(secondFrom, to, importKey + "-2");
        importJobs.save(new TimeToGoImportJobEntity(importKey, secondJobId, "SUBMITTED", secondFrom, to, now, "Submitted to TomTom Traffic Stats"));

        return "Submitted TomTom Traffic Stats import\n"
               + "Import: " + importKey + "\n"
               + "Jobs: " + firstJobId + ", " + secondJobId + "\n"
               + "Range: " + from + " to " + to + "\n"
               + "Routes are split via INA Vukova Gorica to stay under the 200 km limit.\n"
               + "Check later with /time_to_go_import_status";
    }

    public String refreshLatestImport() {
        TimeToGoImportJobEntity job = importJobs.findFirstByOrderByCreatedAtDesc()
                .orElse(null);
        if (job == null) {
            return "No historical import job found. Start one with /time_to_go_import_30.";
        }

        List<TimeToGoImportJobEntity> batch = importJobs.findByImportKeyOrderByCreatedAtAsc(job.getImportKey());
        if (batch.isEmpty()) {
            batch = List.of(job);
        }

        int savedRows = 0;
        int savedJobs = 0;
        StringBuilder statuses = new StringBuilder();
        for (TimeToGoImportJobEntity batchJob : batch) {
            if (batchJob.getSavedAt() != null) {
                savedJobs++;
                statuses.append("\n").append(batchJob.getTomtomJobId()).append(": SAVED");
                continue;
            }

            var status = trafficStats.getStatus(batchJob.getTomtomJobId());
            batchJob.updateStatus(status.jobState(), status.message());
            if (!"DONE".equals(status.jobState())) {
                importJobs.save(batchJob);
                statuses.append("\n").append(batchJob.getTomtomJobId()).append(": ").append(status.jobState());
                continue;
            }

            JsonNode result = trafficStats.downloadJsonResult(status);
            int saved = saveCombinedSummaries(result);
            savedRows += saved;
            savedJobs++;
            batchJob.markSaved("Saved " + saved + " historical combined route samples.");
            importJobs.save(batchJob);
            statuses.append("\n").append(batchJob.getTomtomJobId()).append(": SAVED ").append(saved).append(" rows");
        }

        String header = savedJobs == batch.size()
                ? "Historical Traffic Stats import is saved."
                : "Historical Traffic Stats import is still running.";
        return header
               + "\nImport: " + job.getImportKey()
               + "\nSaved rows this check: " + savedRows
               + statuses;
    }

    private int saveCombinedSummaries(JsonNode result) {
        Map<Integer, LocalDate> dateRanges = parseDateRanges(result.path("dateRanges"));
        Map<Integer, Integer> timeSets = parseTimeSets(result.path("timeSets"));
        Map<String, SegmentAverage> segments = new HashMap<>();
        Set<LocalDate> dates = new HashSet<>(dateRanges.values());
        JsonNode routes = result.path("routes");
        if (!routes.isArray()) {
            throw new IllegalStateException("Traffic Stats result did not include routes");
        }

        for (JsonNode route : routes) {
            String routeName = route.path("routeName").asText(route.path("name").asText(""));
            JsonNode summaries = route.path("summaries");
            if (!summaries.isArray()) continue;

            for (JsonNode summary : summaries) {
                LocalDate date = dateRanges.get(summary.path("dateRange").asInt(-1));
                Integer hour = timeSets.get(summary.path("timeSet").asInt(-1));
                int avgSeconds = (int) Math.round(summary.path("averageTravelTime").asDouble(0));
                int distanceMeters = (int) Math.round(summary.path("distance").asDouble(0) * 1000);
                if (date == null || hour == null || avgSeconds <= 0) continue;
                segments.put(segmentKey(routeName, date, hour), new SegmentAverage(avgSeconds, distanceMeters));
            }
        }

        int saved = 0;
        for (LocalDate date : dates.stream().sorted(Comparator.naturalOrder()).toList()) {
            for (int hour = 0; hour < 24; hour++) {
                saved += saveCombined("zagreb-zadar", date, hour,
                        "zagreb-vukova-gorica", "vukova-gorica-zadar", segments);
                saved += saveCombined("zadar-zagreb", date, hour,
                        "zadar-vukova-gorica", "vukova-gorica-zagreb", segments);
            }
        }
        return saved;
    }

    private static Map<Integer, LocalDate> parseDateRanges(JsonNode dateRanges) {
        Map<Integer, LocalDate> result = new HashMap<>();
        if (!dateRanges.isArray()) {
            return result;
        }

        for (JsonNode dateRange : dateRanges) {
            int id = dateRange.path("@id").asInt(-1);
            LocalDate date = parseDate(dateRange.path("from").asText(dateRange.path("name").asText("")));
            if (id >= 0 && date != null) {
                result.put(id, date);
            }
        }
        return result;
    }

    private static Map<Integer, Integer> parseTimeSets(JsonNode timeSets) {
        Map<Integer, Integer> result = new HashMap<>();
        if (!timeSets.isArray()) {
            return result;
        }

        for (JsonNode timeSet : timeSets) {
            int id = timeSet.path("@id").asInt(-1);
            Integer hour = parseHour(timeSet.path("name").asText(""));
            if (id >= 0 && hour != null) {
                result.put(id, hour);
            }
        }
        return result;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer parseHour(String value) {
        if (value == null || value.length() < 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            return hour >= 0 && hour < 24 ? hour : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int saveCombined(
            String routeKey,
            LocalDate date,
            int hour,
            String firstSegment,
            String secondSegment,
            Map<String, SegmentAverage> segments
    ) {
        SegmentAverage first = segments.get(segmentKey(firstSegment, date, hour));
        SegmentAverage second = segments.get(segmentKey(secondSegment, date, hour));
        if (first == null || second == null) {
            return 0;
        }

        Instant representativeTime = representativeTime(date, hour);
        int travelTime = first.averageTravelTimeSeconds() + second.averageTravelTimeSeconds();
        int distance = first.distanceMeters() + second.distanceMeters();
        snapshots.save(new TimeToGoSnapshotEntity(new TravelTimeSnapshot(
                routeKey,
                representativeTime,
                representativeTime,
                distance,
                travelTime,
                travelTime,
                travelTime,
                travelTime,
                0,
                "TOMTOM_TRAFFIC_STATS"
        )));
        return 1;
    }

    private static Instant representativeTime(LocalDate date, int hour) {
        return ZonedDateTime.of(date, LocalTime.of(hour, 0), ZAGREB).toInstant();
    }

    private static String segmentKey(String routeName, LocalDate date, int hour) {
        return routeName + "|" + date + "|" + hour;
    }

    private record SegmentAverage(int averageTravelTimeSeconds, int distanceMeters) {
    }
}
