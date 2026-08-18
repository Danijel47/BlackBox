package com.blackbox.time_to_go.client;

import com.blackbox.time_to_go.model.TravelRoute;
import com.blackbox.time_to_go.model.TravelTimeSnapshot;
import com.blackbox.time_to_go.properties.TimeToGoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Component
public class TomTomRoutingClient {

    private final RestClient http;
    private final JsonMapper json;
    private final TimeToGoProperties properties;

    public TomTomRoutingClient(JsonMapper json, TimeToGoProperties properties) {
        this.http = RestClient.builder().baseUrl("https://api.tomtom.com").build();
        this.json = json;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.tomtom() != null
               && properties.tomtom().apiKey() != null
               && !properties.tomtom().apiKey().isBlank();
    }

    public TravelTimeSnapshot calculateRoute(TravelRoute route, Instant departureTime) {
        if (!isConfigured()) {
            throw new IllegalStateException("TomTom API key is missing. Set TOMTOM_API_KEY.");
        }

        String locations = route.fromLat() + "," + route.fromLon() + ":" + route.toLat() + "," + route.toLon();
        String body = http.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/routing/1/calculateRoute/{locations}/json")
                        .queryParam("key", properties.tomtom().apiKey())
                        .queryParam("traffic", "true")
                        .queryParam("computeTravelTimeFor", "all")
                        .queryParam("routeRepresentation", "summaryOnly")
                        .queryParam("departAt", DateTimeFormatter.ISO_INSTANT.format(departureTime))
                        .build(locations))
                .retrieve()
                .body(String.class);

        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse TomTom route JSON", e);
        }

        JsonNode summary = root.path("routes").path(0).path("summary");
        if (summary.isMissingNode() || summary.isEmpty()) {
            String message = root.path("detailedError").path("message").asText("");
            throw new IllegalStateException(message.isBlank() ? "TomTom did not return a route summary" : message);
        }

        return new TravelTimeSnapshot(
                route.key(),
                Instant.now(),
                departureTime,
                summary.path("lengthInMeters").asInt(0),
                summary.path("travelTimeInSeconds").asInt(0),
                summary.path("noTrafficTravelTimeInSeconds").asInt(0),
                summary.path("historicTrafficTravelTimeInSeconds").asInt(0),
                summary.path("liveTrafficIncidentsTravelTimeInSeconds").asInt(0),
                summary.path("trafficDelayInSeconds").asInt(0),
                "TOMTOM"
        );
    }
}
