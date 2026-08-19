package com.blackbox.wow.helper;

import com.blackbox.wow.helper.MPlusRunMatcher.LogFight;
import com.blackbox.wow.helper.MPlusRunMatcher.MatchDecision;
import com.blackbox.wow.helper.MPlusRunMatcher.MatchStatus;
import com.blackbox.wow.helper.MPlusRunMatcher.ObservedCandidate;
import com.blackbox.wow.helper.MPlusRunMatcher.PlayerIdentity;
import com.blackbox.wow.properties.MPlusCorrelationProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MPlusRunMatcherTest {

    private static final Instant COMPLETED = Instant.parse("2026-08-19T10:00:00Z");
    private static final MPlusCorrelationProperties PROPERTIES =
            new MPlusCorrelationProperties(180, 30, 4);

    @Test
    void matchesExactlyOneRunWhenAllEvidenceAgrees() {
        MatchDecision result = MPlusRunMatcher.match(
                fight(COMPLETED.plusSeconds(180), 2_030_000, roster("A", "B", "C", "D", "X")),
                List.of(candidate(1, COMPLETED, 2_000_000, roster("A", "B", "C", "D", "E"))),
                PROPERTIES
        );

        assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
        assertThat(result.run().runId()).isEqualTo(1);
        assertThat(result.evidence().timestampDeltaMs()).isEqualTo(180_000);
        assertThat(result.evidence().durationDeltaMs()).isEqualTo(30_000);
        assertThat(result.evidence().rosterOverlap()).isEqualTo(4);
    }

    @Test
    void rejectsCandidatesJustOutsideTimestampDurationOrRosterBoundaries() {
        List<ObservedCandidate> candidates = List.of(
                candidate(1, COMPLETED.minusMillis(180_001), 2_000_000, roster("A", "B", "C", "D")),
                candidate(2, COMPLETED, 1_969_999, roster("A", "B", "C", "D")),
                candidate(3, COMPLETED, 2_000_000, roster("A", "B", "C", "X"))
        );

        MatchDecision result = MPlusRunMatcher.match(
                fight(COMPLETED, 2_000_000, roster("A", "B", "C", "D")), candidates, PROPERTIES
        );

        assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHED);
        assertThat(result.run()).isNull();
    }

    @Test
    void keepsSeveralHighConfidenceCandidatesAmbiguous() {
        List<ObservedCandidate> candidates = List.of(
                candidate(1, COMPLETED, 2_000_000, roster("A", "B", "C", "D")),
                candidate(2, COMPLETED.plusSeconds(1), 2_001_000, roster("A", "B", "C", "D"))
        );

        MatchDecision result = MPlusRunMatcher.match(
                fight(COMPLETED, 2_000_000, roster("A", "B", "C", "D")), candidates, PROPERTIES
        );

        assertThat(result.status()).isEqualTo(MatchStatus.AMBIGUOUS);
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.run()).isNull();
    }

    @Test
    void doesNotTreatSameCharacterNameOnDifferentRealmAsRosterOverlap() {
        MatchDecision result = MPlusRunMatcher.match(
                fight(COMPLETED, 2_000_000, Set.of(
                        identity("A", "Stormscale"), identity("B", "Stormscale"),
                        identity("C", "Stormscale"), identity("D", "Stormscale")
                )),
                List.of(candidate(1, COMPLETED, 2_000_000, Set.of(
                        identity("A", "Draenor"), identity("B", "Stormscale"),
                        identity("C", "Stormscale"), identity("D", "Stormscale")
                ))),
                PROPERTIES
        );

        assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHED);
    }

    @Test
    void requiresSeasonDungeonAndKeyLevelToAgree() {
        ObservedCandidate wrongDungeon = new ObservedCandidate(
                1, "season-mn-2", "Other Dungeon", "OD", 10,
                COMPLETED, 2_000_000, roster("A", "B", "C", "D")
        );

        MatchDecision result = MPlusRunMatcher.match(
                fight(COMPLETED, 2_000_000, roster("A", "B", "C", "D")),
                List.of(wrongDungeon), PROPERTIES
        );

        assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHED);
    }

    private static LogFight fight(Instant endedAt, long durationMs, Set<PlayerIdentity> roster) {
        return new LogFight(
                "season-mn-2", "report", 2, 7, "Ara-Kara", 10,
                endedAt.minusMillis(durationMs), endedAt, durationMs, roster
        );
    }

    private static ObservedCandidate candidate(
            long id,
            Instant completedAt,
            long durationMs,
            Set<PlayerIdentity> roster
    ) {
        return new ObservedCandidate(
                id, "season-mn-2", "Ara-Kara", "AK", 10,
                completedAt, durationMs, roster
        );
    }

    private static Set<PlayerIdentity> roster(String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> identity(name, "Stormscale"))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static PlayerIdentity identity(String name, String realm) {
        return new PlayerIdentity("eu", realm, name);
    }
}
