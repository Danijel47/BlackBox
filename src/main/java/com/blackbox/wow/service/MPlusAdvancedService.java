package com.blackbox.wow.service;

import com.blackbox.wow.helper.MPlusAdvancedMetrics;
import com.blackbox.wow.helper.MPlusAdvancedMetrics.Consistency;
import com.blackbox.wow.helper.MPlusAdvancedMetrics.DungeonProfile;
import com.blackbox.wow.properties.MPlusAdvancedProperties;
import com.blackbox.wow.repository.MPlusAdvancedRepository;
import com.blackbox.wow.repository.MPlusAdvancedRepository.ModifierRunRow;
import com.blackbox.wow.repository.MPlusPerformanceRepository;
import com.blackbox.wow.repository.MPlusPerformanceRepository.ObservedRun;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

@Service
public class MPlusAdvancedService {

    private static final double TIE_TOLERANCE = 0.000_001;

    private final TrackedPlayerService trackedPlayerService;
    private final MPlusPlayerResolver playerResolver;
    private final MPlusProgressRepository progressRepository;
    private final MPlusPerformanceRepository performanceRepository;
    private final MPlusAdvancedRepository advancedRepository;
    private final MPlusAdvancedProperties properties;

    public MPlusAdvancedService(
            TrackedPlayerService trackedPlayerService,
            MPlusPlayerResolver playerResolver,
            MPlusProgressRepository progressRepository,
            MPlusPerformanceRepository performanceRepository,
            MPlusAdvancedRepository advancedRepository,
            MPlusAdvancedProperties properties
    ) {
        this.trackedPlayerService = trackedPlayerService;
        this.playerResolver = playerResolver;
        this.progressRepository = progressRepository;
        this.performanceRepository = performanceRepository;
        this.advancedRepository = advancedRepository;
        this.properties = properties;
    }

    public String consistencyMessage(String argument, Long telegramUserId) {
        PlayerRuns data = loadPlayerRuns(argument, telegramUserId);
        if (data.error() != null) {
            return data.error();
        }
        if (data.runs().size() < properties.minimumIndividualRuns()) {
            return insufficientRuns(data.player().profileName(), data.season(), data.runs().size());
        }
        Consistency consistency = MPlusAdvancedMetrics.consistency(
                data.runs(), properties.comfortCoveragePercent()
        );
        DungeonProfile dungeon = MPlusAdvancedMetrics.dungeonProfile(data.runs());
        int clutchRuns = MPlusAdvancedMetrics.clutchCount(data.runs(), properties.clutchWindowSeconds());
        return formatConsistency(data, consistency, dungeon, clutchRuns);
    }

    public String affixMessage(String argument, Long telegramUserId) {
        PlayerRuns data = loadPlayerRuns(argument, telegramUserId);
        if (data.error() != null) {
            return data.error();
        }
        List<AffixRun> enrichedRuns = buildAffixRuns(
                advancedRepository.modifierRunRows(data.player().profileId(), data.season())
        );
        Map<String, AffixSummary> comparable = summarizeAffixes(enrichedRuns);
        if (comparable.size() < 2) {
            return insufficientAffixData(data, enrichedRuns.size(), comparable.size());
        }
        return formatAffixes(data, enrichedRuns.size(), comparable);
    }

    public String awardsMessage() {
        if (!properties.awardsEnabled()) {
            return "M+ awards are disabled. An admin can opt in with WOW_MPLUS_AWARDS_ENABLED=true.";
        }
        List<PlayerRuns> candidates = loadAwardCandidates();
        if (candidates.size() < 2) {
            return "M+ awards need at least 2 eligible profiles with "
                    + properties.minimumIndividualRuns() + " observed runs in the same season.";
        }
        return formatAwards(candidates);
    }

    private PlayerRuns loadPlayerRuns(String argument, Long telegramUserId) {
        Resolution resolution = playerResolver.resolveSelfOrNamed(argument, telegramUserId);
        if (resolution.error() != null) {
            return PlayerRuns.error(resolution.error());
        }
        TrackedPlayer player = resolution.player();
        Optional<ScorePoint> latest = progressRepository.latestScore(player.profileId());
        if (latest.isEmpty()) {
            return PlayerRuns.error("Advanced M+ metrics for " + player.profileName()
                    + " are unavailable until its first collection completes.");
        }
        String season = latest.get().season();
        List<ObservedRun> runs = validRuns(player.profileId(), season);
        return PlayerRuns.found(player, season, runs);
    }

    private List<PlayerRuns> loadAwardCandidates() {
        List<PlayerScore> playerScores = trackedPlayerService.activePlayers().stream()
                .map(this::loadPlayerScore)
                .flatMap(Optional::stream)
                .toList();
        Optional<PlayerScore> newest = playerScores.stream()
                .max(Comparator.comparing(score -> score.point().capturedAt()));
        if (newest.isEmpty()) {
            return List.of();
        }
        String season = newest.get().point().season();
        return playerScores.stream()
                .filter(score -> season.equals(score.point().season()))
                .map(score -> PlayerRuns.found(
                        score.player(), season, validRuns(score.player().profileId(), season)
                ))
                .filter(data -> data.runs().size() >= properties.minimumIndividualRuns())
                .toList();
    }

    private Optional<PlayerScore> loadPlayerScore(TrackedPlayer player) {
        return progressRepository.latestScore(player.profileId())
                .filter(point -> point.capturedAt() != null)
                .map(point -> new PlayerScore(player, point));
    }

    private List<ObservedRun> validRuns(long profileId, String season) {
        return performanceRepository.observedRuns(profileId, season).stream()
                .filter(MPlusAdvancedService::hasValidDuration)
                .toList();
    }

    private String formatConsistency(
            PlayerRuns data,
            Consistency consistency,
            DungeonProfile dungeon,
            int clutchRuns
    ) {
        var comfort = consistency.comfortRange();
        String specialist = dungeon.concentrationPercent() >= properties.specialistMinimumPercent()
                ? "Specialist concentration: " + dungeon.mostPlayedDungeon() + " — "
                    + formatPercent(dungeon.concentrationPercent()) + "% (" + dungeon.mostPlayedRuns() + " runs)"
                : "Specialist concentration: none above " + properties.specialistMinimumPercent() + "%";
        return "Advanced observed M+ metrics — " + data.player().profileName() + " — " + data.season() + '\n'
                + "Sample: N=" + data.runs().size() + " deduplicated observed runs\n"
                + "Consistency (population SD of key levels): " + formatNumber(consistency.standardDeviation()) + '\n'
                + "IQR (Q3 − Q1): " + formatNumber(consistency.interquartileRange())
                + " (Q1=" + formatNumber(consistency.firstQuartile())
                + ", Q3=" + formatNumber(consistency.thirdQuartile()) + ")\n"
                + "Comfort range: +" + comfort.minimumLevel() + " to +" + comfort.maximumLevel()
                + " (smallest interval containing " + comfort.includedRuns() + '/' + comfort.totalRuns()
                + " runs; target " + properties.comfortCoveragePercent() + "%)\n"
                + specialist + '\n'
                + "Dungeon coverage: " + dungeon.uniqueDungeons() + " unique dungeons\n"
                + "Clutch runs: " + clutchRuns + " timed with 1–" + properties.clutchWindowSeconds()
                + " seconds remaining\n"
                + "Observed data only; Raider.IO may not expose every completed run.";
    }

    private String formatAwards(List<PlayerRuns> candidates) {
        List<AwardCandidate> metrics = candidates.stream().map(this::awardCandidate).toList();
        String season = candidates.getFirst().season();
        StringBuilder message = new StringBuilder("Observed M+ awards — ")
                .append(season).append('\n')
                .append("Eligible: ").append(metrics.size()).append(" profiles; minimum N=")
                .append(properties.minimumIndividualRuns()).append(" each\n");
        appendLowestDoubleAward(message, "Most consistent", metrics, AwardCandidate::standardDeviation,
                "lowest population SD of key levels");
        appendHighestDoubleAward(message, "Dungeon specialist", eligibleSpecialists(metrics),
                AwardCandidate::concentrationPercent, "highest share of runs in one dungeon");
        appendHighestIntAward(message, "Dungeon tourist", eligibleTourists(metrics),
                AwardCandidate::uniqueDungeons,
                "most unique dungeons with no dungeon at or above the specialist threshold");
        appendHighestIntAward(message, "Clutch", eligibleClutch(metrics), AwardCandidate::clutchRuns,
                "most timed runs with 1–" + properties.clutchWindowSeconds() + " seconds remaining");
        return message.append("All ties are shown. Observed data only; re-importing a run does not increase N.\n")
                .append("Shared-run awards require N=").append(properties.minimumSharedRuns())
                .append(" and are not ranked until that metric is available.")
                .toString();
    }

    private AwardCandidate awardCandidate(PlayerRuns data) {
        Consistency consistency = MPlusAdvancedMetrics.consistency(
                data.runs(), properties.comfortCoveragePercent()
        );
        DungeonProfile dungeon = MPlusAdvancedMetrics.dungeonProfile(data.runs());
        return new AwardCandidate(
                data.player().profileName(), data.runs().size(), consistency.standardDeviation(),
                dungeon.concentrationPercent(), dungeon.uniqueDungeons(),
                MPlusAdvancedMetrics.clutchCount(data.runs(), properties.clutchWindowSeconds())
        );
    }

    private List<AwardCandidate> eligibleSpecialists(List<AwardCandidate> metrics) {
        return metrics.stream()
                .filter(candidate -> candidate.concentrationPercent() >= properties.specialistMinimumPercent())
                .toList();
    }

    private List<AwardCandidate> eligibleTourists(List<AwardCandidate> metrics) {
        return metrics.stream()
                .filter(candidate -> candidate.concentrationPercent() < properties.specialistMinimumPercent())
                .toList();
    }

    private static List<AwardCandidate> eligibleClutch(List<AwardCandidate> metrics) {
        return metrics.stream().filter(candidate -> candidate.clutchRuns() > 0).toList();
    }

    private static void appendLowestDoubleAward(
            StringBuilder message,
            String label,
            List<AwardCandidate> candidates,
            ToDoubleFunction<AwardCandidate> metric,
            String formula
    ) {
        if (candidates.isEmpty()) {
            appendUnavailableAward(message, label, formula);
            return;
        }
        double winningValue = candidates.stream().mapToDouble(metric).min().orElseThrow();
        List<AwardCandidate> winners = candidates.stream()
                .filter(candidate -> Math.abs(metric.applyAsDouble(candidate) - winningValue) <= TIE_TOLERANCE)
                .toList();
        appendAward(message, label, winners, formula, formatNumber(winningValue));
    }

    private static void appendHighestDoubleAward(
            StringBuilder message,
            String label,
            List<AwardCandidate> candidates,
            ToDoubleFunction<AwardCandidate> metric,
            String formula
    ) {
        if (candidates.isEmpty()) {
            appendUnavailableAward(message, label, formula);
            return;
        }
        double winningValue = candidates.stream().mapToDouble(metric).max().orElseThrow();
        List<AwardCandidate> winners = candidates.stream()
                .filter(candidate -> Math.abs(metric.applyAsDouble(candidate) - winningValue) <= TIE_TOLERANCE)
                .toList();
        appendAward(message, label, winners, formula, formatPercent(winningValue) + "%");
    }

    private static void appendHighestIntAward(
            StringBuilder message,
            String label,
            List<AwardCandidate> candidates,
            ToIntFunction<AwardCandidate> metric,
            String formula
    ) {
        if (candidates.isEmpty()) {
            appendUnavailableAward(message, label, formula);
            return;
        }
        int winningValue = candidates.stream().mapToInt(metric).max().orElseThrow();
        List<AwardCandidate> winners = candidates.stream()
                .filter(candidate -> metric.applyAsInt(candidate) == winningValue)
                .toList();
        appendAward(message, label, winners, formula, Integer.toString(winningValue));
    }

    private static void appendAward(
            StringBuilder message,
            String label,
            List<AwardCandidate> winners,
            String formula,
            String value
    ) {
        String names = winners.stream()
                .map(winner -> winner.profileName() + " (N=" + winner.sampleSize() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        message.append(label).append(": ").append(names).append(" — ").append(value)
                .append(" [formula: ").append(formula).append("]\n");
    }

    private static void appendUnavailableAward(StringBuilder message, String label, String formula) {
        message.append(label).append(": unavailable [formula: ").append(formula).append("]\n");
    }

    private Map<String, AffixSummary> summarizeAffixes(List<AffixRun> runs) {
        Map<String, List<AffixRun>> grouped = new LinkedHashMap<>();
        for (AffixRun run : runs) {
            grouped.computeIfAbsent(run.combination(), ignored -> new ArrayList<>()).add(run);
        }
        Map<String, AffixSummary> summaries = new LinkedHashMap<>();
        grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= properties.minimumIndividualRuns())
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> summaries.put(entry.getKey(), AffixSummary.from(entry.getValue())));
        return summaries;
    }

    private String formatAffixes(PlayerRuns data, int enrichedRunCount, Map<String, AffixSummary> summaries) {
        StringBuilder message = new StringBuilder("Observed M+ modifier comparison — ")
                .append(data.player().profileName()).append(" — ").append(data.season()).append('\n')
                .append("Modifier coverage: ").append(enrichedRunCount).append('/').append(data.runs().size())
                .append(" observed runs; each row requires N=").append(properties.minimumIndividualRuns()).append('\n');
        summaries.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, AffixSummary> entry) -> entry.getValue().runs())
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(properties.maximumAffixRows())
                .forEach(entry -> appendAffixSummary(message, entry.getKey(), entry.getValue()));
        return message.append("Formula: average key and timed percentage per exact modifier combination.\n")
                .append("Observed data only; combinations are read from the current season, not hard-coded.")
                .toString();
    }

    private String insufficientAffixData(PlayerRuns data, int enrichedRuns, int comparableGroups) {
        return "Modifier comparison for " + data.player().profileName() + " — " + data.season()
                + " is unavailable: " + enrichedRuns + '/' + data.runs().size()
                + " observed runs include modifier data, and " + comparableGroups
                + " combinations meet N=" + properties.minimumIndividualRuns()
                + ". At least 2 qualifying combinations are required.";
    }

    private String insufficientRuns(String profileName, String season, int sampleSize) {
        return "Advanced M+ metrics for " + profileName + " — " + season + " are unavailable (N="
                + sampleSize + ", need " + properties.minimumIndividualRuns()
                + " deduplicated observed runs).";
    }

    private static void appendAffixSummary(StringBuilder message, String name, AffixSummary summary) {
        message.append("• ").append(name).append(" — N=").append(summary.runs())
                .append(", average key +").append(formatNumber(summary.averageKey()))
                .append(", timed ").append(formatPercent(summary.timedPercent())).append("%\n");
    }

    static List<AffixRun> buildAffixRuns(List<ModifierRunRow> rows) {
        Map<Long, AffixRunBuilder> builders = new LinkedHashMap<>();
        for (ModifierRunRow row : rows) {
            builders.computeIfAbsent(row.storedRunId(), ignored -> new AffixRunBuilder(row))
                    .addModifier(modifierLabel(row));
        }
        return builders.values().stream().map(AffixRunBuilder::build).toList();
    }

    private static String modifierLabel(ModifierRunRow row) {
        if (row.modifierName() != null && !row.modifierName().isBlank()) {
            return row.modifierName().trim();
        }
        if (row.modifierSlug() != null && !row.modifierSlug().isBlank()) {
            return row.modifierSlug().trim();
        }
        return "Modifier " + row.modifierId();
    }

    private static boolean hasValidDuration(ObservedRun run) {
        return run.clearTimeMs() > 0 && run.parTimeMs() > 0;
    }

    private static String formatNumber(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatPercent(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private record PlayerScore(TrackedPlayer player, ScorePoint point) {
    }

    private record PlayerRuns(TrackedPlayer player, String season, List<ObservedRun> runs, String error) {
        private static PlayerRuns found(TrackedPlayer player, String season, List<ObservedRun> runs) {
            return new PlayerRuns(player, season, runs, null);
        }

        private static PlayerRuns error(String error) {
            return new PlayerRuns(null, null, List.of(), error);
        }
    }

    private record AwardCandidate(
            String profileName,
            int sampleSize,
            double standardDeviation,
            double concentrationPercent,
            int uniqueDungeons,
            int clutchRuns
    ) {
    }

    record AffixRun(long storedRunId, String combination, int mythicLevel, boolean timed) {
    }

    private record AffixSummary(int runs, double averageKey, double timedPercent) {
        private static AffixSummary from(List<AffixRun> runs) {
            double average = runs.stream().mapToInt(AffixRun::mythicLevel).average().orElse(0);
            long timed = runs.stream().filter(AffixRun::timed).count();
            return new AffixSummary(runs.size(), average, timed * 100.0 / runs.size());
        }
    }

    private static final class AffixRunBuilder {
        private final long storedRunId;
        private final int mythicLevel;
        private final boolean timed;
        private final TreeSet<String> modifiers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        private AffixRunBuilder(ModifierRunRow row) {
            storedRunId = row.storedRunId();
            mythicLevel = row.mythicLevel();
            timed = row.timed();
        }

        private AffixRunBuilder addModifier(String modifier) {
            modifiers.add(modifier);
            return this;
        }

        private AffixRun build() {
            return new AffixRun(storedRunId, String.join(" + ", modifiers), mythicLevel, timed);
        }
    }
}
