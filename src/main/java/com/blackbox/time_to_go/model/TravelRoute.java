package com.blackbox.time_to_go.model;

import java.util.Locale;

public enum TravelRoute {
    ZADAR_TO_ZAGREB("zadar-zagreb", "Zadar -> Zagreb", 44.1194, 15.2314, 45.8150, 15.9819),
    ZAGREB_TO_ZADAR("zagreb-zadar", "Zagreb -> Zadar", 45.8150, 15.9819, 44.1194, 15.2314);

    private final String key;
    private final String label;
    private final double fromLat;
    private final double fromLon;
    private final double toLat;
    private final double toLon;

    TravelRoute(String key, String label, double fromLat, double fromLon, double toLat, double toLon) {
        this.key = key;
        this.label = label;
        this.fromLat = fromLat;
        this.fromLon = fromLon;
        this.toLat = toLat;
        this.toLon = toLon;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public double fromLat() {
        return fromLat;
    }

    public double fromLon() {
        return fromLon;
    }

    public double toLat() {
        return toLat;
    }

    public double toLon() {
        return toLon;
    }

    public static TravelRoute fromCities(String from, String to) {
        String normalized = normalize(from) + "-" + normalize(to);
        for (TravelRoute route : values()) {
            if (route.key.equals(normalized)) {
                return route;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
