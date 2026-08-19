package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.helper.MPlusResetCalendar;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.Milestone;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.TrackedPlayerService.PlayerProfile;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MPlusProgressService {

    private static final DateTimeFormatter MILESTONE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TrackedPlayerService trackedPlayerService;
    private final MPlusProgressRepository repository;
    private final MPlusProgressProperties properties;
    private final MPlusResetCalendar resetCalendar;
    private final Clock clock;

    public MPlusProgressService(
            TrackedPlayerService trackedPlayerService,
            MPlusProgressRepository repository,
            MPlusProgressProperties properties,
            Clock clock
    ) {
        this.trackedPlayerService = trackedPlayerService;
        this.repository = repository;
        this.properties = properties;
        this.resetCalendar = new MPlusResetCalendar(properties);
        this.clock = clock;
    }

    public String progressMessage(String argument, Long telegramUserId) {
        String normalizedArgument = argument == null ? "" : argument.trim();
        if (normalizedArgument.isEmpty()) {
            return groupProgressMessage();
        }
        if ("me".equalsIgnoreCase(normalizedArgument)) {
            return ownProgressMessage(telegramUserId);
        }
        return namedProfileMessage(normalizedArgument);
    }

    private String groupProgressMessage() {
        List<TrackedPlayer> players = trackedPlayerService.activePlayers();
        if (players.isEmpty()) {
            return "No active player profiles have a selected character.";
        }
        Map<Long, ScorePoint> latestScores = loadLatestScores(players);
        Optional<ScorePoint> freshestScore = latestScores.values().stream()
                .filter(score -> score.score() != null)
                .max(Comparator.comparing(ScorePoint::capturedAt));
        if (freshestScore.isEmpty()) {
            return "Mythic+ progress is unavailable until the first score collection completes.";
        }
        String season = freshestScore.get().season();
        Instant now = clock.instant();
        Instant resetBoundary = previousReset(now);
        List<ProgressRow> rows = buildRows(players, latestScores, season, resetBoundary);
        rows.sort(progressComparator(players));
        return formatGroupMessage(rows, season, now);
    }

    private String ownProgressMessage(Long telegramUserId) {
        if (telegramUserId == null) {
            return "Telegram user information is unavailable for this message.";
        }
        Optional<PlayerProfile> profile = trackedPlayerService.profileForTelegramUser(telegramUserId);
        if (profile.isEmpty()) {
            return "No player profile is linked to your Telegram account. Ask the bot admin to link it.";
        }
        return namedProfileMessage(profile.get().name());
    }

    private String namedProfileMessage(String profileName) {
        Optional<TrackedPlayer> player = trackedPlayerService.activePlayers().stream()
                .filter(candidate -> candidate.profileName().equalsIgnoreCase(profileName))
                .findFirst();
        if (player.isEmpty()) {
            return "Active player profile not found: " + profileName;
        }
        Optional<ScorePoint> latest = repository.latestScore(player.get().profileId());
        if (latest.isEmpty() || latest.get().score() == null) {
            return "Mythic+ progress for " + player.get().profileName()
                    + " is unavailable until its first score collection completes.";
        }
        return formatProfileMessage(player.get(), latest.get());
    }

    private Map<Long, ScorePoint> loadLatestScores(List<TrackedPlayer> players) {
        Map<Long, ScorePoint> scores = new HashMap<>();
        for (TrackedPlayer player : players) {
            repository.latestScore(player.profileId()).ifPresent(score -> scores.put(player.profileId(), score));
        }
        return scores;
    }

    private List<ProgressRow> buildRows(
            List<TrackedPlayer> players,
            Map<Long, ScorePoint> latestScores,
            String season,
            Instant resetBoundary
    ) {
        List<ProgressRow> rows = new ArrayList<>();
        for (TrackedPlayer player : players) {
            ScorePoint latest = latestScores.get(player.profileId());
            if (latest == null || latest.score() == null || !season.equals(latest.season())) {
                rows.add(new ProgressRow(player, null, null));
                continue;
            }
            BigDecimal resetGain = repository.latestScoreAtOrBefore(
                            player.profileId(), season, resetBoundary
                    )
                    .map(baseline -> latest.score().subtract(baseline.score()))
                    .orElse(null);
            rows.add(new ProgressRow(player, latest, resetGain));
        }
        return rows;
    }

    private static Comparator<ProgressRow> progressComparator(List<TrackedPlayer> players) {
        Map<Long, Integer> displayOrder = new HashMap<>();
        for (int index = 0; index < players.size(); index++) {
            displayOrder.put(players.get(index).profileId(), index);
        }
        return Comparator
                .comparing(ProgressRow::score, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(row -> displayOrder.get(row.player().profileId()));
    }

    private String formatGroupMessage(List<ProgressRow> rows, String season, Instant now) {
        StringBuilder message = new StringBuilder("Mythic+ progress — ").append(season).append('\n');
        int rank = 1;
        for (ProgressRow row : rows) {
            message.append(rank++).append(". ").append(row.player().profileName()).append(" — ");
            if (row.latest() == null) {
                message.append("unavailable for this season\n");
                continue;
            }
            message.append(formatScore(row.latest().score()))
                    .append(" (")
                    .append(formatGain(row.resetGain()))
                    .append(" this reset, updated ")
                    .append(formatAge(row.latest().capturedAt(), now))
                    .append(" ago)\n");
        }
        bestResetGain(rows).ifPresent(best -> message.append("\nBiggest reset gain: ")
                .append(best.player().profileName())
                .append(" (")
                .append(formatGain(best.resetGain()))
                .append(")\n"));
        return message.append("Scores are periodic snapshots; milestone dates are first observed.").toString();
    }

    private String formatProfileMessage(TrackedPlayer player, ScorePoint latest) {
        Instant now = clock.instant();
        Optional<ScorePoint> yesterday = repository.latestScoreAtOrBefore(
                player.profileId(), latest.season(), now.minus(Duration.ofDays(1))
        );
        Optional<ScorePoint> reset = repository.latestScoreAtOrBefore(
                player.profileId(), latest.season(), previousReset(now)
        );
        Optional<ScorePoint> first = repository.firstScore(player.profileId(), latest.season());
        List<Milestone> milestones = repository.milestones(player.profileId(), latest.season());

        StringBuilder message = new StringBuilder("Mythic+ progress — ")
                .append(player.profileName())
                .append(" — ")
                .append(latest.season())
                .append('\n')
                .append("Current: ").append(formatScore(latest.score()))
                .append(" (updated ").append(formatAge(latest.capturedAt(), now)).append(" ago)\n")
                .append("Last 24h: ").append(gainFrom(latest, yesterday)).append('\n')
                .append("This reset: ").append(gainFrom(latest, reset)).append('\n')
                .append("Since first observed: ").append(gainFrom(latest, first)).append('\n')
                .append("Observed character: ").append(latest.characterName()).append('-').append(latest.realm());
        appendMilestones(message, milestones);
        return message.append("\nSnapshots show observed progress, not history from before collection started.")
                .toString();
    }

    private void appendMilestones(StringBuilder message, List<Milestone> milestones) {
        message.append("\nMilestones: ");
        if (milestones.isEmpty()) {
            message.append("none observed yet");
            return;
        }
        for (int index = 0; index < milestones.size(); index++) {
            if (index > 0) {
                message.append(", ");
            }
            Milestone milestone = milestones.get(index);
            LocalDate date = milestone.firstObservedAt().atZone(properties.resetZone()).toLocalDate();
            message.append(formatScore(milestone.milestoneScore()))
                    .append(" on ")
                    .append(MILESTONE_DATE.format(date));
        }
    }

    private Instant previousReset(Instant now) {
        return resetCalendar.periodStart(now);
    }

    private static Optional<ProgressRow> bestResetGain(List<ProgressRow> rows) {
        return rows.stream()
                .filter(row -> row.resetGain() != null)
                .max(Comparator.comparing(ProgressRow::resetGain));
    }

    private static String gainFrom(ScorePoint latest, Optional<ScorePoint> baseline) {
        return baseline.map(point -> formatGain(latest.score().subtract(point.score())))
                .orElse("unavailable");
    }

    private static String formatGain(BigDecimal gain) {
        if (gain == null) {
            return "unavailable";
        }
        String prefix = gain.signum() >= 0 ? "+" : "";
        return prefix + formatScore(gain);
    }

    private static String formatScore(BigDecimal score) {
        return score.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatAge(Instant timestamp, Instant now) {
        Duration age = Duration.between(timestamp, now);
        if (age.isNegative() || age.toMinutes() < 1) {
            return "less than a minute";
        }
        if (age.toHours() < 1) {
            return age.toMinutes() + "m";
        }
        if (age.toDays() < 1) {
            return age.toHours() + "h";
        }
        return age.toDays() + "d";
    }

    private record ProgressRow(TrackedPlayer player, ScorePoint latest, BigDecimal resetGain) {
        private BigDecimal score() {
            return latest == null ? null : latest.score();
        }
    }
}
