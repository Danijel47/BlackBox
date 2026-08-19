package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusTeamProperties;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.repository.MPlusTeamRepository;
import com.blackbox.wow.repository.MPlusTeamRepository.TeamRunMemberRow;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MPlusTeamService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final TrackedPlayerService trackedPlayerService;
    private final MPlusPlayerResolver playerResolver;
    private final MPlusProgressRepository progressRepository;
    private final MPlusTeamRepository teamRepository;
    private final MPlusTeamProperties properties;

    public MPlusTeamService(
            TrackedPlayerService trackedPlayerService,
            MPlusPlayerResolver playerResolver,
            MPlusProgressRepository progressRepository,
            MPlusTeamRepository teamRepository,
            MPlusTeamProperties properties
    ) {
        this.trackedPlayerService = trackedPlayerService;
        this.playerResolver = playerResolver;
        this.progressRepository = progressRepository;
        this.teamRepository = teamRepository;
        this.properties = properties;
    }

    public String teamMessage(String argument, Long telegramUserId) {
        String requestedProfile = argument == null ? "" : argument.trim();
        SeasonRuns seasonRuns = loadCurrentSeasonRuns();
        if (seasonRuns.error() != null) {
            return seasonRuns.error();
        }
        if (requestedProfile.isBlank()) {
            return formatGroupSummary(seasonRuns.season(), seasonRuns.runs());
        }
        Resolution resolution = playerResolver.resolveSelfOrNamed(requestedProfile, telegramUserId);
        if (resolution.error() != null) {
            return resolution.error();
        }
        return formatProfileSummary(resolution.player(), seasonRuns.season(), seasonRuns.runs());
    }

    public String pairMessage(String arguments) {
        String[] profileNames = arguments == null ? new String[0] : arguments.trim().split("\\s+");
        if (profileNames.length != 2) {
            return "Usage: /mplus pair <profile-a> <profile-b>";
        }
        Map<String, TrackedPlayer> activePlayers = trackedPlayerService.activePlayers().stream()
                .collect(Collectors.toMap(
                        player -> player.profileName().toLowerCase(Locale.ROOT),
                        Function.identity()
                ));
        TrackedPlayer first = activePlayers.get(profileNames[0].toLowerCase(Locale.ROOT));
        TrackedPlayer second = activePlayers.get(profileNames[1].toLowerCase(Locale.ROOT));
        if (first == null || second == null || first.profileId() == second.profileId()) {
            return "Provide two different active player profiles.";
        }
        SeasonRuns seasonRuns = loadCurrentSeasonRuns();
        if (seasonRuns.error() != null) {
            return seasonRuns.error();
        }
        List<TeamRun> sharedRuns = runsContainingBoth(seasonRuns.runs(), first.profileId(), second.profileId());
        return formatPair(first.profileName(), second.profileName(), seasonRuns.season(), sharedRuns);
    }

    private SeasonRuns loadCurrentSeasonRuns() {
        List<TrackedPlayer> activePlayers = trackedPlayerService.activePlayers();
        Optional<ScorePoint> freshest = activePlayers.stream()
                .map(player -> progressRepository.latestScore(player.profileId()).orElse(null))
                .filter(score -> score != null && score.score() != null)
                .max(Comparator.comparing(ScorePoint::capturedAt));
        if (freshest.isEmpty()) {
            return SeasonRuns.error("Team analytics are unavailable until score collection completes.");
        }
        Set<Long> activeProfileIds = activePlayers.stream()
                .map(TrackedPlayer::profileId)
                .collect(Collectors.toUnmodifiableSet());
        List<TeamRun> runs = buildRuns(
                teamRepository.teamRunRows(freshest.get().season()),
                activeProfileIds
        );
        if (runs.isEmpty()) {
            return SeasonRuns.error("No roster-enriched observed runs are available for "
                    + freshest.get().season() + ".");
        }
        return SeasonRuns.found(freshest.get().season(), runs);
    }

    private String formatGroupSummary(String season, List<TeamRun> runs) {
        Map<PairKey, List<TeamRun>> pairs = pairRuns(runs);
        StringBuilder message = new StringBuilder("Observed M+ team synergy — ")
                .append(season)
                .append('\n')
                .append("Based on ")
                .append(runs.size())
                .append(" roster-enriched observed runs.\n")
                .append("Most frequent pairs:\n");
        if (pairs.isEmpty()) {
            message.append("• No tracked teammate pairs observed.\n");
        } else {
            pairs.entrySet().stream()
                    .sorted(pairEntryComparator())
                    .limit(properties.maximumRows())
                    .forEach(entry -> message.append("• ")
                            .append(entry.getKey().label())
                            .append(" — ")
                            .append(entry.getValue().size())
                            .append(" shared runs\n"));
        }
        appendMostFrequentGroup(message, runs);
        appendMostCommonComposition(message, runs);
        return message.append("Together means both profiles were matched in the same observed roster.")
                .toString();
    }

    private String formatProfileSummary(TrackedPlayer player, String season, List<TeamRun> allRuns) {
        List<TeamRun> playerRuns = allRuns.stream()
                .filter(run -> run.profiles().containsKey(player.profileId()))
                .toList();
        if (playerRuns.isEmpty()) {
            return "No roster-enriched observed runs are available for " + player.profileName()
                    + " in " + season + ".";
        }
        Map<Long, TeammateRuns> teammates = new HashMap<>();
        for (TeamRun run : playerRuns) {
            run.profiles().forEach((profileId, profileName) -> {
                if (profileId != player.profileId()) {
                    teammates.computeIfAbsent(profileId, ignored -> new TeammateRuns(profileName))
                            .runs().add(run);
                }
            });
        }
        StringBuilder message = new StringBuilder("Observed M+ teammates — ")
                .append(player.profileName())
                .append(" — ")
                .append(season)
                .append('\n')
                .append("Based on ")
                .append(playerRuns.size())
                .append(" observed runs from ")
                .append(observationPeriod(playerRuns))
                .append(".\n");
        if (teammates.isEmpty()) {
            message.append("No other tracked profile was identified in these rosters.\n");
        } else {
            teammates.values().stream()
                    .sorted(Comparator.comparingInt((TeammateRuns value) -> value.runs().size())
                            .reversed()
                            .thenComparing(TeammateRuns::profileName))
                    .limit(properties.maximumRows())
                    .forEach(teammate -> appendTeammate(message, teammate));
        }
        List<TeamRun> withoutTrackedFriends = playerRuns.stream()
                .filter(run -> run.profiles().size() == 1)
                .toList();
        message.append("Without another tracked profile identified: ")
                .append(formatCompactMetrics(withoutTrackedFriends))
                .append('\n');
        return message.append("This does not mean solo play; unmatched roster members are not tracked friends.")
                .toString();
    }

    private String formatPair(String first, String second, String season, List<TeamRun> sharedRuns) {
        StringBuilder message = new StringBuilder("Observed M+ pair — ")
                .append(first)
                .append(" + ")
                .append(second)
                .append(" — ")
                .append(season)
                .append('\n');
        if (sharedRuns.isEmpty()) {
            return message.append("No shared observed runs with both profiles matched.").toString();
        }
        message.append("Shared runs: ").append(sharedRuns.size())
                .append(" (").append(observationPeriod(sharedRuns)).append(")\n")
                .append("Average key: +").append(formatDecimal(averageKey(sharedRuns))).append('\n')
                .append("Highest key: +").append(highestKey(sharedRuns)).append('\n');
        appendTimedPercentage(message, sharedRuns);
        appendSharedHighlights(message, sharedRuns);
        return message.append("Based only on deduplicated observed runs.").toString();
    }

    private void appendTeammate(StringBuilder message, TeammateRuns teammate) {
        message.append("• ").append(teammate.profileName()).append(" — ")
                .append(teammate.runs().size()).append(" runs, avg +")
                .append(formatDecimal(averageKey(teammate.runs())))
                .append(", high +").append(highestKey(teammate.runs()));
        if (teammate.runs().size() >= properties.minimumSharedRuns()) {
            message.append(", ").append(formatTimedPercentage(teammate.runs())).append(" timed");
        } else {
            message.append(", timed rate needs ").append(properties.minimumSharedRuns()).append(" runs");
        }
        message.append('\n');
    }

    private void appendTimedPercentage(StringBuilder message, List<TeamRun> runs) {
        if (runs.size() < properties.minimumSharedRuns()) {
            message.append("Timed percentage: unavailable (N=")
                    .append(runs.size()).append(", need ")
                    .append(properties.minimumSharedRuns()).append(")\n");
        } else {
            message.append("Timed percentage: ").append(formatTimedPercentage(runs)).append("\n");
        }
    }

    private static void appendSharedHighlights(StringBuilder message, List<TeamRun> runs) {
        TeamRun fastest = runs.stream().min(Comparator.comparingLong(TeamRun::clearTimeMs)).orElseThrow();
        Optional<TeamRun> clutch = runs.stream()
                .filter(run -> run.timed() && run.timeRemainingMs() >= 0)
                .min(Comparator.comparingLong(TeamRun::timeRemainingMs));
        message.append("Fastest: +").append(fastest.mythicLevel()).append(' ')
                .append(fastest.dungeon()).append(" in ").append(formatDuration(fastest.clearTimeMs())).append('\n');
        clutch.ifPresent(run -> message.append("Closest timed: +")
                .append(run.mythicLevel()).append(' ').append(run.dungeon())
                .append(" with ").append(formatDuration(run.timeRemainingMs())).append(" left\n"));
    }

    private static void appendMostFrequentGroup(StringBuilder message, List<TeamRun> runs) {
        Map<String, Long> groups = runs.stream()
                .filter(run -> run.profiles().size() >= 2)
                .collect(Collectors.groupingBy(TeamRun::profileLabel, Collectors.counting()));
        groups.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey))
                .ifPresent(entry -> message.append("Most frequent tracked group: ")
                        .append(entry.getKey()).append(" (").append(entry.getValue()).append(" runs)\n"));
    }

    private static void appendMostCommonComposition(StringBuilder message, List<TeamRun> runs) {
        Map<String, Long> compositions = runs.stream()
                .map(TeamRun::composition)
                .filter(composition -> !composition.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        compositions.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey))
                .ifPresent(entry -> message.append("Most common composition: ")
                        .append(entry.getKey()).append(" (").append(entry.getValue()).append(" runs)\n"));
    }

    private static Map<PairKey, List<TeamRun>> pairRuns(List<TeamRun> runs) {
        Map<PairKey, List<TeamRun>> pairs = new HashMap<>();
        for (TeamRun run : runs) {
            List<Map.Entry<Long, String>> profiles = new ArrayList<>(run.profiles().entrySet());
            profiles.sort(Map.Entry.comparingByKey());
            for (int first = 0; first < profiles.size(); first++) {
                for (int second = first + 1; second < profiles.size(); second++) {
                    PairKey pair = new PairKey(profiles.get(first), profiles.get(second));
                    pairs.computeIfAbsent(pair, ignored -> new ArrayList<>()).add(run);
                }
            }
        }
        return pairs;
    }

    private static Comparator<Map.Entry<PairKey, List<TeamRun>>> pairEntryComparator() {
        return Comparator.<Map.Entry<PairKey, List<TeamRun>>>comparingInt(entry -> entry.getValue().size())
                .reversed()
                .thenComparing(entry -> entry.getKey().label());
    }

    private static List<TeamRun> runsContainingBoth(List<TeamRun> runs, long first, long second) {
        return runs.stream()
                .filter(run -> run.profiles().containsKey(first) && run.profiles().containsKey(second))
                .toList();
    }

    static List<TeamRun> buildRuns(List<TeamRunMemberRow> rows, Set<Long> activeProfileIds) {
        Map<Long, TeamRunBuilder> builders = new LinkedHashMap<>();
        for (TeamRunMemberRow row : rows) {
            TeamRunBuilder builder = builders.computeIfAbsent(
                    row.storedRunId(),
                    ignored -> new TeamRunBuilder(row)
            );
            builder.addMember(row, activeProfileIds);
        }
        return builders.values().stream().map(TeamRunBuilder::build).toList();
    }

    private static String observationPeriod(Collection<TeamRun> runs) {
        Instant first = runs.stream().map(TeamRun::completedAt).min(Instant::compareTo).orElseThrow();
        Instant last = runs.stream().map(TeamRun::completedAt).max(Instant::compareTo).orElseThrow();
        return DATE.format(first) + " to " + DATE.format(last);
    }

    private static String formatCompactMetrics(List<TeamRun> runs) {
        if (runs.isEmpty()) {
            return "none observed";
        }
        return runs.size() + " runs, avg +" + formatDecimal(averageKey(runs))
                + ", high +" + highestKey(runs);
    }

    private static BigDecimal averageKey(List<TeamRun> runs) {
        return BigDecimal.valueOf(runs.stream().mapToInt(TeamRun::mythicLevel).sum())
                .divide(BigDecimal.valueOf(runs.size()), 1, RoundingMode.HALF_UP);
    }

    private static int highestKey(List<TeamRun> runs) {
        return runs.stream().mapToInt(TeamRun::mythicLevel).max().orElse(0);
    }

    private static String formatTimedPercentage(List<TeamRun> runs) {
        long timed = runs.stream().filter(TeamRun::timed).count();
        return BigDecimal.valueOf(timed * 100L)
                .divide(BigDecimal.valueOf(runs.size()), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static String formatDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0, Math.round(milliseconds / 1_000.0));
        return totalSeconds / 60 + "m " + totalSeconds % 60 + "s";
    }

    record TeamRun(
            long storedRunId,
            String dungeon,
            int mythicLevel,
            Instant completedAt,
            long clearTimeMs,
            long parTimeMs,
            boolean timed,
            Map<Long, String> profiles,
            List<String> memberSignatures
    ) {
        private long timeRemainingMs() {
            return parTimeMs - clearTimeMs;
        }

        private String profileLabel() {
            return profiles.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(" + "));
        }

        private String composition() {
            return memberSignatures.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
        }
    }

    private static final class TeamRunBuilder {
        private final TeamRunMemberRow run;
        private final Map<Long, String> profiles = new LinkedHashMap<>();
        private final List<String> members = new ArrayList<>();

        private TeamRunBuilder(TeamRunMemberRow run) {
            this.run = run;
        }

        private void addMember(TeamRunMemberRow row, Set<Long> activeProfileIds) {
            if (row.matchedProfileId() != null && activeProfileIds.contains(row.matchedProfileId())) {
                profiles.put(row.matchedProfileId(), row.profileName());
            }
            String role = blankFallback(row.role(), "Unknown role");
            String characterClass = blankFallback(row.className(), "Unknown class");
            members.add(role + " " + characterClass);
        }

        private TeamRun build() {
            return new TeamRun(
                    run.storedRunId(),
                    run.dungeonShortName(),
                    run.mythicLevel(),
                    run.completedAt(),
                    run.clearTimeMs(),
                    run.parTimeMs(),
                    run.timed(),
                    Map.copyOf(profiles),
                    List.copyOf(members)
            );
        }

        private static String blankFallback(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private record PairKey(Map.Entry<Long, String> first, Map.Entry<Long, String> second) {
        private String label() {
            return first.getValue() + " + " + second.getValue();
        }
    }

    private record TeammateRuns(String profileName, List<TeamRun> runs) {
        private TeammateRuns(String profileName) {
            this(profileName, new ArrayList<>());
        }
    }

    private record SeasonRuns(String season, List<TeamRun> runs, String error) {
        private static SeasonRuns found(String season, List<TeamRun> runs) {
            return new SeasonRuns(season, runs, null);
        }

        private static SeasonRuns error(String error) {
            return new SeasonRuns(null, List.of(), error);
        }
    }
}
