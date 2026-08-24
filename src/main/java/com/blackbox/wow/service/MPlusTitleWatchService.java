package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class MPlusTitleWatchService {

    private static final Duration REPORT_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration PREDICTION_CACHE_TTL = Duration.ofMinutes(30);
    private static final String PERCENTILE_ONE = "p990";
    private static final String PERCENTILE_POINT_ONE = "p999";
    private static final String PROFILE_DATA_UNAVAILABLE = "profile data unavailable";
    private static final ZoneId ZAGREB_ZONE = ZoneId.of("Europe/Zagreb");

    private final RaiderIoClient raiderIoClient;
    private final TrackedPlayerService trackedPlayerService;
    private final Clock clock;
    private ReportCache onePercentReportCache;
    private ReportCache pointOnePercentReportCache;
    private PredictionCache onePercentPredictionCache;
    private PredictionCache pointOnePercentPredictionCache;

    public MPlusTitleWatchService(
            RaiderIoClient raiderIoClient,
            TrackedPlayerService trackedPlayerService,
            Clock clock
    ) {
        this.raiderIoClient = raiderIoClient;
        this.trackedPlayerService = trackedPlayerService;
        this.clock = clock;
    }

    public synchronized String onePercentReport() {
        List<TrackedPlayer> players = trackedPlayerService.titleWatchPlayers();
        if (players.isEmpty()) {
            return "No title watch players configured.";
        }
        return report(
                "M+ 1% title watch",
                "M+ 1% title watch is unavailable because Raider.IO returned no cutoff score.",
                PERCENTILE_ONE,
                players
        );
    }

    public synchronized String pointOnePercentReport() {
        List<TrackedPlayer> players = trackedPlayerService.activePlayers();
        if (players.isEmpty()) {
            return "No active profiles are configured for the 0.1% title watch.";
        }
        return report(
                "M+ 0.1% title watch",
                "M+ 0.1% title watch is unavailable because Raider.IO returned no cutoff score.",
                PERCENTILE_POINT_ONE,
                players
        );
    }

    private String report(
            String heading,
            String unavailableMessage,
            String percentileKey,
            List<TrackedPlayer> players
    ) {
        Instant now = clock.instant();
        ReportCache currentCache = reportCache(percentileKey);
        if (isCurrent(currentCache, players, now)) {
            return currentCache.message();
        }

        String region = players.getFirst().region();
        RaiderIoClient.MPlusTitleCutoff cutoff = loadCutoff(region, percentileKey);
        BigDecimal cutoffScore = cutoff.score();
        if (cutoffScore == null) {
            return unavailableMessage;
        }

        List<TitleWatchResult> results = loadResults(players, cutoffScore);
        String message = formatReport(heading, percentileKey, region, cutoff, results);
        setReportCache(percentileKey, new ReportCache(message, players, now.plus(REPORT_CACHE_TTL)));
        return message;
    }

    private RaiderIoClient.MPlusTitleCutoff loadCutoff(String region, String percentileKey) {
        if (PERCENTILE_ONE.equals(percentileKey)) {
            return raiderIoClient.getCurrentMPlusTitleCutoff(region);
        }
        return raiderIoClient.getCurrentMPlusTitleCutoff(region, percentileKey);
    }

    private ReportCache reportCache(String percentileKey) {
        return PERCENTILE_POINT_ONE.equals(percentileKey)
                ? pointOnePercentReportCache
                : onePercentReportCache;
    }

    private void setReportCache(String percentileKey, ReportCache cache) {
        if (PERCENTILE_POINT_ONE.equals(percentileKey)) {
            pointOnePercentReportCache = cache;
        } else {
            onePercentReportCache = cache;
        }
    }

    private static boolean isCurrent(ReportCache cache, List<TrackedPlayer> players, Instant now) {
        return cache != null
                && cache.expiresAt().isAfter(now)
                && cache.players().equals(players);
    }

    private List<TitleWatchResult> loadResults(List<TrackedPlayer> players, BigDecimal cutoffScore) {
        List<TitleWatchResult> results = new ArrayList<>();
        for (TrackedPlayer player : players) {
            results.add(loadResult(player, cutoffScore));
        }
        results.sort(Comparator.comparing(
                TitleWatchResult::score,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return results;
    }

    private TitleWatchResult loadResult(TrackedPlayer player, BigDecimal cutoffScore) {
        try {
            RaiderIoClient.RaiderIoScore score = raiderIoClient.getCurrentMPlusScore(
                    player.region(),
                    player.realm(),
                    player.name()
            );
            BigDecimal currentScore = score.all();
            ScoreDelta delta = calculateDelta(currentScore, cutoffScore);
            return new TitleWatchResult(
                    score.name(),
                    currentScore,
                    delta.remaining(),
                    delta.above(),
                    null
            );
        } catch (RuntimeException _) {
            return new TitleWatchResult(player.name(), null, null, null, PROFILE_DATA_UNAVAILABLE);
        }
    }

    private String formatReport(
            String heading,
            String percentileKey,
            String region,
            RaiderIoClient.MPlusTitleCutoff cutoff,
            List<TitleWatchResult> results
    ) {
        StringBuilder message = new StringBuilder(heading).append('\n');
        message.append("Cutoff: ").append(formatScore(cutoff.score()))
                .append("\nCutoff updated: ").append(formatCutoffUpdatedAt(cutoff.updatedAt()))
                .append('\n');
        appendPrediction(message, percentileKey, region);
        results.forEach(result -> appendResult(message, result));
        return message.toString().trim();
    }

    private static void appendResult(StringBuilder message, TitleWatchResult result) {
        message.append("• ").append(result.name()).append(": ");
        if (result.error() != null) {
            message.append("error: ").append(result.error()).append('\n');
            return;
        }
        message.append(formatScoreLine(result)).append('\n');
    }

    private void appendPrediction(StringBuilder message, String percentileKey, String region) {
        RaiderIoClient.MPlusTitlePrediction prediction = cachedPrediction(percentileKey, region);
        if (prediction == null) {
            message.append("Predicted season end: n/a\n");
            return;
        }
        message.append("Predicted season end: ")
                .append(formatScore(prediction.predictedScore()))
                .append(" (").append(formatPredictionDate(prediction.predictionFor())).append(")\n");
    }

    private RaiderIoClient.MPlusTitlePrediction cachedPrediction(String percentileKey, String region) {
        Instant now = clock.instant();
        PredictionCache cache = predictionCache(percentileKey);
        if (isCurrent(cache, region, now)) {
            return cache.prediction();
        }
        try {
            RaiderIoClient.MPlusTitlePrediction prediction =
                    raiderIoClient.getCurrentMPlusTitlePrediction(region, percentileKey);
            setPredictionCache(
                    percentileKey,
                    new PredictionCache(prediction, region, now.plus(PREDICTION_CACHE_TTL))
            );
            return prediction;
        } catch (RuntimeException _) {
            return cache == null || !cache.region().equalsIgnoreCase(region) ? null : cache.prediction();
        }
    }

    private PredictionCache predictionCache(String percentileKey) {
        return PERCENTILE_POINT_ONE.equals(percentileKey)
                ? pointOnePercentPredictionCache
                : onePercentPredictionCache;
    }

    private void setPredictionCache(String percentileKey, PredictionCache cache) {
        if (PERCENTILE_POINT_ONE.equals(percentileKey)) {
            pointOnePercentPredictionCache = cache;
        } else {
            onePercentPredictionCache = cache;
        }
    }

    private static boolean isCurrent(PredictionCache cache, String region, Instant now) {
        return cache != null
                && cache.expiresAt().isAfter(now)
                && cache.region().equalsIgnoreCase(region);
    }

    private static String formatScoreLine(TitleWatchResult result) {
        if (result.score() == null) {
            return "n/a";
        }
        if (result.remaining() == null) {
            return formatScore(result.score()) + " | remaining n/a";
        }
        if (result.remaining().compareTo(BigDecimal.ZERO) == 0) {
            return formatScore(result.score()) + " | above by " + formatScore(result.above());
        }
        return formatScore(result.score()) + " | remaining " + formatScore(result.remaining());
    }

    private static ScoreDelta calculateDelta(BigDecimal score, BigDecimal cutoffScore) {
        if (score == null || cutoffScore == null) {
            return new ScoreDelta(null, null);
        }
        return new ScoreDelta(
                cutoffScore.subtract(score).max(BigDecimal.ZERO),
                score.subtract(cutoffScore).max(BigDecimal.ZERO)
        );
    }

    private static String formatScore(BigDecimal score) {
        if (score == null) {
            return "n/a";
        }
        return score.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatPredictionDate(Instant predictionFor) {
        if (predictionFor == null) {
            return "n/a";
        }
        return predictionFor.atZone(ZAGREB_ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String formatCutoffUpdatedAt(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) {
            return "n/a";
        }
        try {
            ZonedDateTime utc = ZonedDateTime.parse(
                    updatedAt,
                    DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z '('zzzz')'", Locale.ENGLISH)
            );
            return utc.withZoneSameInstant(ZAGREB_ZONE)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        } catch (RuntimeException _) {
            return updatedAt;
        }
    }

    private record ScoreDelta(BigDecimal remaining, BigDecimal above) {
    }

    private record TitleWatchResult(
            String name,
            BigDecimal score,
            BigDecimal remaining,
            BigDecimal above,
            String error
    ) {
    }

    private record ReportCache(String message, List<TrackedPlayer> players, Instant expiresAt) {
    }

    private record PredictionCache(
            RaiderIoClient.MPlusTitlePrediction prediction,
            String region,
            Instant expiresAt
    ) {
    }
}
