package com.blackbox.wow.helper;

import com.blackbox.wow.properties.MPlusCorrelationProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class MPlusRunMatcher {

    private MPlusRunMatcher() {
    }

    public static MatchDecision match(
            LogFight fight,
            List<ObservedCandidate> observedRuns,
            MPlusCorrelationProperties properties
    ) {
        List<MatchEvidence> candidates = observedRuns.stream()
                .filter(run -> fight.season().equals(run.season()))
                .filter(run -> run.mythicLevel() == fight.mythicLevel())
                .filter(run -> sameDungeon(fight.dungeonName(), run))
                .map(run -> evidence(fight, run))
                .filter(evidence -> withinLimits(evidence, properties))
                .toList();
        if (candidates.isEmpty()) {
            return new MatchDecision(MatchStatus.UNMATCHED, null, 0, null);
        }
        if (candidates.size() > 1) {
            return new MatchDecision(MatchStatus.AMBIGUOUS, null, candidates.size(), null);
        }
        return new MatchDecision(MatchStatus.MATCHED, candidates.getFirst().run(), 1, candidates.getFirst());
    }

    private static MatchEvidence evidence(LogFight fight, ObservedCandidate run) {
        long timestampDelta = absoluteMillis(Duration.between(run.completedAt(), fight.endedAt()));
        long durationDelta = Math.abs(run.clearTimeMs() - fight.durationMs());
        int overlap = rosterOverlap(fight.roster(), run.roster());
        return new MatchEvidence(run, timestampDelta, durationDelta, overlap);
    }

    private static boolean withinLimits(MatchEvidence evidence, MPlusCorrelationProperties properties) {
        return evidence.timestampDeltaMs() <= properties.timestampToleranceSeconds() * 1_000L
                && evidence.durationDeltaMs() <= properties.durationToleranceSeconds() * 1_000L
                && evidence.rosterOverlap() >= properties.minimumRosterOverlap();
    }

    private static int rosterOverlap(Set<PlayerIdentity> left, Set<PlayerIdentity> right) {
        Set<String> rightIdentities = right.stream()
                .map(MPlusRunMatcher::normalizedIdentity)
                .collect(Collectors.toSet());
        return Math.toIntExact(left.stream()
                .map(MPlusRunMatcher::normalizedIdentity)
                .filter(rightIdentities::contains)
                .distinct()
                .count());
    }

    private static boolean sameDungeon(String logDungeon, ObservedCandidate run) {
        String normalized = normalize(logDungeon);
        return normalized.equals(normalize(run.dungeonName()))
                || normalized.equals(normalize(run.dungeonShortName()));
    }

    private static String normalizedIdentity(PlayerIdentity identity) {
        return normalize(identity.region()) + ':' + normalize(identity.realm()) + ':' + normalize(identity.name());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    private static long absoluteMillis(Duration duration) {
        long milliseconds = duration.toMillis();
        return milliseconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(milliseconds);
    }

    public enum MatchStatus {
        MATCHED,
        UNMATCHED,
        AMBIGUOUS,
        REJECTED
    }

    public record PlayerIdentity(String region, String realm, String name) {
    }

    public record LogFight(
            String season,
            String reportCode,
            int reportRevision,
            int fightId,
            String dungeonName,
            int mythicLevel,
            Instant startedAt,
            Instant endedAt,
            long durationMs,
            Set<PlayerIdentity> roster
    ) {
        public LogFight {
            roster = Set.copyOf(roster);
        }
    }

    public record ObservedCandidate(
            long runId,
            String season,
            String dungeonName,
            String dungeonShortName,
            int mythicLevel,
            Instant completedAt,
            long clearTimeMs,
            Set<PlayerIdentity> roster
    ) {
        public ObservedCandidate {
            roster = Set.copyOf(roster);
        }
    }

    public record MatchEvidence(
            ObservedCandidate run,
            long timestampDeltaMs,
            long durationDeltaMs,
            int rosterOverlap
    ) {
    }

    public record MatchDecision(
            MatchStatus status,
            ObservedCandidate run,
            int candidateCount,
            MatchEvidence evidence
    ) {
    }
}
