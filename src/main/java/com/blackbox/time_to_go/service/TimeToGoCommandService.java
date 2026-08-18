package com.blackbox.time_to_go.service;

import com.blackbox.time_to_go.model.TravelRoute;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

@Service
public class TimeToGoCommandService {

    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    private final TimeToGoService timeToGo;
    private final TimeToGoHistoricalImportService historicalImport;

    public TimeToGoCommandService(TimeToGoService timeToGo, TimeToGoHistoricalImportService historicalImport) {
        this.timeToGo = timeToGo;
        this.historicalImport = historicalImport;
    }

    public String formatCurrent(String text) {
        TravelRoute route = parseRoute(text);
        if (route == null && text.trim().split("\\s+").length > 1) {
            return usage();
        }

        if (route != null) {
            return timeToGo.getCombinedLatest(route)
                    .map(TimeToGoCommandService::formatCombined)
                    .orElseGet(() -> noSamples(route));
        }

        return timeToGo.getCombinedLatest(TravelRoute.ZADAR_TO_ZAGREB)
                .map(TimeToGoCommandService::formatCombined)
                .orElseGet(() -> noSamples(TravelRoute.ZADAR_TO_ZAGREB))
               + "\n\n"
               + timeToGo.getCombinedLatest(TravelRoute.ZAGREB_TO_ZADAR)
                .map(TimeToGoCommandService::formatCombined)
                .orElseGet(() -> noSamples(TravelRoute.ZAGREB_TO_ZADAR));
    }

    public String formatBest(String text) {
        TravelRoute route = parseRoute(text);
        if (route == null) {
            route = TravelRoute.ZADAR_TO_ZAGREB;
        }

        List<TimeToGoService.HistoricalAverage> best = timeToGo.bestHistoricalSlots(route, 8);
        if (best.isEmpty()) {
            return "No time to go history yet for " + route.label() + ". Wait for scheduled samples at :00 and :30.";
        }

        StringBuilder sb = new StringBuilder("Best historical slots\n").append(route.label()).append("\n");
        for (TimeToGoService.HistoricalAverage avg : best) {
            sb.append("• ")
                    .append(avg.day().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .append(" ")
                    .append(String.format("%02d:00", avg.hour()))
                    .append(": ")
                    .append(formatDuration(avg.averageTravelTimeSeconds()))
                    .append(" avg (").append(avg.samples()).append(" samples)")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String submitHistoricalImport() {
        return historicalImport.submitLast30DaysImport();
    }

    public String refreshHistoricalImport() {
        return historicalImport.refreshLatestImport();
    }

    private static String formatCombined(TimeToGoService.CombinedTravelTime result) {
        var current = result.current();
        StringBuilder sb = new StringBuilder("Time to go\n");
        sb.append(result.route().label()).append("\n");
        sb.append("Now: ").append(formatDuration(current.travelTimeSeconds()))
                .append(" | delay ").append(formatDuration(current.trafficDelaySeconds()))
                .append("\n");
        sb.append("No traffic: ").append(formatDuration(current.noTrafficTravelTimeSeconds()))
                .append(" | historic: ").append(formatDuration(current.historicTrafficTravelTimeSeconds()))
                .append("\n");

        var local = current.departureTime().atZone(ZAGREB);
        sb.append("Latest sample: ").append(local.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")));

        if (result.matchingAverage() == null) {
            sb.append("\nHistory for this weekday/hour: n/a");
        } else {
            var avg = result.matchingAverage();
            sb.append("\nHistory for this weekday/hour: ")
                    .append(formatDuration(avg.averageTravelTimeSeconds()))
                    .append(" avg (").append(avg.samples()).append(" samples)");
        }
        return sb.toString();
    }

    private static TravelRoute parseRoute(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 3) {
            return null;
        }
        return TravelRoute.fromCities(parts[1], parts[2]);
    }

    private static String usage() {
        return "Usage: /road [zadar zagreb|zagreb zadar]\n"
               + "Best historical slots: /roadbest [zadar zagreb|zagreb zadar]\n"
               + "Samples are collected automatically at :00 and :30.";
    }

    private static String noSamples(TravelRoute route) {
        return "Time to go\n" + route.label() + "\nNo saved samples yet. Wait for the scheduled sampler at :00 or :30.";
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) {
            return "n/a";
        }
        int minutes = Math.round(seconds / 60.0f);
        int hours = minutes / 60;
        int remainingMinutes = minutes % 60;
        if (hours == 0) {
            return remainingMinutes + " min";
        }
        return hours + "h " + remainingMinutes + "m";
    }
}
