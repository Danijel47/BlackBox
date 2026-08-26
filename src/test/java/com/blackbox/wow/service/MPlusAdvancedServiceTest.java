package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusAdvancedProperties;
import com.blackbox.wow.repository.MPlusPerformanceRepository;
import com.blackbox.wow.repository.MPlusPerformanceRepository.ObservedRun;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusAdvancedServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Mock private MPlusPlayerResolver playerResolver;
    @Mock private MPlusProgressRepository progressRepository;
    @Mock private MPlusPerformanceRepository performanceRepository;

    @Test
    void refusesToPublishMetricsBelowTheConfiguredSample() {
        TrackedPlayer buco = player(1, "Buco");
        prepareResolvedPlayer(buco, List.of(run(1, "AD", 10, 20)));

        String message = service(false, 2).consistencyMessage("", 123L);

        assertThat(message).contains("unavailable (N=1, need 2 deduplicated observed runs)");
    }

    private void prepareResolvedPlayer(TrackedPlayer player, List<ObservedRun> runs) {
        when(playerResolver.resolveSelfOrNamed("", 123L)).thenReturn(Resolution.found(player));
        when(progressRepository.latestScore(player.profileId())).thenReturn(Optional.of(score(player.profileId())));
        when(performanceRepository.observedRuns(player.profileId(), "season-mn-2")).thenReturn(runs);
    }

    private MPlusAdvancedService service(boolean awardsEnabled, int minimumRuns) {
        return new MPlusAdvancedService(
                playerResolver,
                progressRepository,
                performanceRepository,
                new MPlusAdvancedProperties(awardsEnabled, minimumRuns, 5, 70, 40, 60)
        );
    }

    private static TrackedPlayer player(long id, String name) {
        return new TrackedPlayer(id, name, "eu", "Stormscale", name + "char");
    }

    private static ScorePoint score(long profileId) {
        return new ScorePoint(
                profileId, "season-mn-2", BigDecimal.valueOf(2_000), NOW,
                "eu", "Stormscale", "Character"
        );
    }

    private static ObservedRun run(long id, String dungeon, int level, long remainingSeconds) {
        long parTime = 2_000_000;
        return new ObservedRun(
                id, dungeon + " Dungeon", dungeon, level, NOW.minusSeconds(id),
                parTime - remainingSeconds * 1_000, parTime,
                remainingSeconds >= 0 ? 1 : 0, BigDecimal.TEN, remainingSeconds >= 0
        );
    }

}
