package com.blackbox.time_to_go.client;

import com.blackbox.time_to_go.properties.TimeToGoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

@Component
public class TomTomTrafficStatsClient {

    private static final List<DayOfWeek> DAYS = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
    );

    private final RestClient tomTom;
    private final RestClient raw;
    private final JsonMapper json;
    private final TimeToGoProperties properties;

    public TomTomTrafficStatsClient(JsonMapper json, TimeToGoProperties properties) {
        this.tomTom = RestClient.builder().baseUrl("https://api.tomtom.com").build();
        this.raw = RestClient.builder().build();
        this.json = json;
        this.properties = properties;
    }

    public String submitHistoricalJob(LocalDate from, LocalDate to, String jobName) {
        String body = tomTom.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/traffic/trafficstats/routeanalysis/1")
                        .queryParam("key", apiKey())
                .build())
                .header("Content-Type", "application/json")
                .body(buildJobRequest(from, to, jobName).toString())
                .retrieve()
                .body(String.class);

        JsonNode root = readTree(body);
        String jobId = root.path("jobId").asText("");
        if (jobId.isBlank()) {
            throw new IllegalStateException("TomTom Traffic Stats did not return a jobId: " + body);
        }
        return jobId;
    }

    public TrafficStatsStatus getStatus(String jobId) {
        String body = tomTom.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/traffic/trafficstats/status/1/{jobId}")
                        .queryParam("key", apiKey())
                        .build(jobId))
                .retrieve()
                .body(String.class);

        JsonNode root = readTree(body);
        List<String> urls = new ArrayList<>();
        JsonNode urlsNode = root.path("urls");
        if (urlsNode.isArray()) {
            for (JsonNode url : urlsNode) {
                urls.add(url.asText(""));
            }
        }
        return new TrafficStatsStatus(
                root.path("jobId").asText(jobId),
                root.path("jobState").asText("UNKNOWN"),
                root.path("responseStatus").asText(""),
                urls,
                root.path("messages").isArray() && !root.path("messages").isEmpty()
                        ? root.path("messages").get(0).asText("")
                        : ""
        );
    }

    public JsonNode downloadJsonResult(TrafficStatsStatus status) {
        String url = status.urls().stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).contains("json"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TomTom Traffic Stats result did not include a JSON URL"));

        byte[] body = raw.get()
                .uri(url)
                .retrieve()
                .body(byte[].class);

        if (body == null || body.length == 0) {
            throw new IllegalStateException("TomTom Traffic Stats JSON result was empty");
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return json.readTree(new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception gzipError) {
            try {
                return json.readTree(body);
            } catch (Exception jsonError) {
                throw new IllegalStateException("Failed to parse TomTom Traffic Stats JSON result", jsonError);
            }
        }
    }

    private ObjectNode buildJobRequest(LocalDate from, LocalDate to, String jobName) {
        ObjectNode root = json.createObjectNode();
        root.put("jobName", jobName);
        root.put("distanceUnit", "KILOMETERS");
        root.put("acceptMode", "AUTO");

        ArrayNode routes = root.putArray("routes");
        addRoute(routes, "zagreb-vukova-gorica", 45.8150, 15.9819, 45.45565, 15.37755);
        addRoute(routes, "vukova-gorica-zadar", 45.45565, 15.37755, 44.1194, 15.2314);
        addRoute(routes, "zadar-vukova-gorica", 44.1194, 15.2314, 45.45565, 15.37755);
        addRoute(routes, "vukova-gorica-zagreb", 45.45565, 15.37755, 45.8150, 15.9819);

        ArrayNode dateRanges = root.putArray("dateRanges");
        LocalDate date = from;
        while (!date.isAfter(to)) {
            ObjectNode dateRange = dateRanges.addObject();
            dateRange.put("name", date.toString());
            dateRange.put("from", date.toString());
            dateRange.put("to", date.toString());
            date = date.plusDays(1);
        }

        ArrayNode timeSets = root.putArray("timeSets");
        for (int hour = 0; hour < 24; hour++) {
            ObjectNode timeSet = timeSets.addObject();
            timeSet.put("name", String.format("%02d:00", hour));
            ObjectNode timeGroup = timeSet.putArray("timeGroups").addObject();
            ArrayNode days = timeGroup.putArray("days");
            DAYS.stream().map(TomTomTrafficStatsClient::dayCode).forEach(days::add);
            timeGroup.putArray("times").add(String.format("%02d:00-%02d:00", hour, hour + 1));
        }

        return root;
    }

    private static void addRoute(ArrayNode routes, String name, double startLat, double startLon, double endLat, double endLon) {
        ObjectNode route = routes.addObject();
        route.put("name", name);
        route.put("fullTraversal", false);
        route.put("zoneId", "Europe/Zagreb");
        route.put("probeSource", "ALL");
        route.putObject("start").put("latitude", startLat).put("longitude", startLon);
        route.putObject("end").put("latitude", endLat).put("longitude", endLon);
    }

    private String apiKey() {
        String apiKey = properties.tomtom() == null ? null : properties.tomtom().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TomTom API key is missing. Set TOMTOM_API_KEY.");
        }
        return apiKey;
    }

    private JsonNode readTree(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse TomTom Traffic Stats JSON", e);
        }
    }

    private static String dayCode(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "MON";
            case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED";
            case THURSDAY -> "THU";
            case FRIDAY -> "FRI";
            case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
    }

    public record TrafficStatsStatus(
            String jobId,
            String jobState,
            String responseStatus,
            List<String> urls,
            String message
    ) {
    }
}
