package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusTeamProperties;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.repository.MPlusTeamRepository;
import com.blackbox.wow.repository.MPlusTeamRepository.TeamRunMemberRow;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusTeamServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private MPlusPlayerResolver playerResolver;
    @Mock private MPlusProgressRepository progressRepository;
    @Mock private MPlusTeamRepository teamRepository;

    @Test
    void deduplicatesOneRunAndKeepsUnmatchedRosterMembersUnlinked() {
        List<MPlusTeamService.TeamRun> runs = MPlusTeamService.buildRuns(List.of(
                row(1, 1L, "Buco", "Bucothered"),
                row(1, 2L, "Linq", "Thelinq"),
                row(1, null, null, "Unknownmage")
        ), Set.of(1L, 2L));

        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.profiles()).containsOnlyKeys(1L, 2L);
            assertThat(run.memberSignatures()).hasSize(3);
        });
    }

    @Test
    void requiresTheConfiguredSampleBeforePublishingPairTimedPercentage() {
        TrackedPlayer buco = player(1, "Buco");
        TrackedPlayer linq = player(2, "Linq");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, linq));
        when(progressRepository.latestScore(1)).thenReturn(Optional.of(score(1)));
        when(progressRepository.latestScore(2)).thenReturn(Optional.of(score(2)));
        when(teamRepository.teamRunRows("season-mn-2")).thenReturn(List.of(
                row(1, 1L, "Buco", "Bucothered"),
                row(1, 2L, "Linq", "Thelinq"),
                row(2, 1L, "Buco", "Bucothered"),
                row(2, 2L, "Linq", "Thelinq")
        ));

        String message = service().pairMessage("Buco Linq");

        assertThat(message)
                .contains("Shared runs: 2")
                .contains("Average key: +10.0")
                .contains("Timed percentage: unavailable (N=2, need 3)")
                .contains("Based only on deduplicated observed runs");
    }

    @Test
    void reportsRunsWithoutTrackedFriendsWithoutCallingThemSolo() {
        TrackedPlayer buco = player(1, "Buco");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco));
        when(progressRepository.latestScore(1)).thenReturn(Optional.of(score(1)));
        when(teamRepository.teamRunRows("season-mn-2")).thenReturn(List.of(
                row(1, 1L, "Buco", "Bucothered"),
                row(1, null, null, "Unknownmage")
        ));
        when(playerResolver.resolveSelfOrNamed("Buco", 123L))
                .thenReturn(MPlusPlayerResolver.Resolution.found(buco));

        String message = service().teamMessage("Buco", 123L);

        assertThat(message)
                .contains("Without another tracked profile identified: 1 runs")
                .contains("This does not mean solo play");
    }

    private MPlusTeamService service() {
        return new MPlusTeamService(
                trackedPlayerService,
                playerResolver,
                progressRepository,
                teamRepository,
                new MPlusTeamProperties(3, 5)
        );
    }

    private static TrackedPlayer player(long id, String name) {
        return new TrackedPlayer(id, name, "eu", "Stormscale", name + "char");
    }

    private static ScorePoint score(long profileId) {
        return new ScorePoint(
                profileId,
                "season-mn-2",
                new BigDecimal("2000"),
                NOW,
                "eu",
                "Stormscale",
                "Character"
        );
    }

    private static TeamRunMemberRow row(
            long runId,
            Long profileId,
            String profileName,
            String characterName
    ) {
        return new TeamRunMemberRow(
                runId,
                1000 + runId,
                "AD",
                10,
                NOW.minusSeconds(runId * 60),
                1_800_000,
                2_000_000,
                true,
                profileId,
                profileName,
                characterName,
                "Mage",
                "Arcane",
                "DPS"
        );
    }
}
