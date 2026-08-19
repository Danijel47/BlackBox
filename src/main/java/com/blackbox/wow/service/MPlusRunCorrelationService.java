package com.blackbox.wow.service;

import com.blackbox.wow.helper.MPlusRunMatcher;
import com.blackbox.wow.helper.MPlusRunMatcher.LogFight;
import com.blackbox.wow.helper.MPlusRunMatcher.MatchDecision;
import com.blackbox.wow.helper.MPlusRunMatcher.MatchStatus;
import com.blackbox.wow.properties.MPlusCorrelationProperties;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.repository.MPlusRunCorrelationRepository;
import com.blackbox.wow.repository.MPlusRunCorrelationRepository.Coverage;
import com.blackbox.wow.repository.MPlusRunCorrelationRepository.StatusCount;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
public class MPlusRunCorrelationService {

    private final MPlusRunCorrelationRepository correlationRepository;
    private final MPlusCorrelationProperties properties;
    private final MPlusPlayerResolver playerResolver;
    private final MPlusProgressRepository progressRepository;

    public MPlusRunCorrelationService(
            MPlusRunCorrelationRepository correlationRepository,
            MPlusCorrelationProperties properties,
            MPlusPlayerResolver playerResolver,
            MPlusProgressRepository progressRepository
    ) {
        this.correlationRepository = correlationRepository;
        this.properties = properties;
        this.playerResolver = playerResolver;
        this.progressRepository = progressRepository;
    }

    @Transactional
    public void correlate(LogFight fight) {
        MatchDecision decision = MPlusRunMatcher.match(
                fight,
                correlationRepository.observedCandidates(fight.season(), fight.mythicLevel()),
                properties
        );
        if (isClaimedByAnotherFight(decision, fight)) {
            decision = new MatchDecision(MatchStatus.REJECTED, null, decision.candidateCount(), null);
        }
        correlationRepository.saveDecision(
                fight.season(), fight.reportCode(), fight.reportRevision(), fight.fightId(), decision
        );
    }

    public String coverageMessage(String argument, Long telegramUserId) {
        Resolution resolution = playerResolver.resolveSelfOrNamed(argument, telegramUserId);
        if (resolution.error() != null) {
            return resolution.error();
        }
        TrackedPlayer player = resolution.player();
        Optional<ScorePoint> latest = progressRepository.latestScore(player.profileId());
        if (latest.isEmpty()) {
            return "M+ log coverage for " + player.profileName()
                    + " is unavailable until its first Raider.IO collection completes.";
        }
        Coverage coverage = correlationRepository.coverage(player.profileId(), latest.get().season());
        return formatCoverage(player.profileName(), latest.get().season(), coverage);
    }

    public String statusMessage(String season) {
        List<StatusCount> counts = correlationRepository.statusCounts(season);
        if (counts.isEmpty()) {
            return "M+ / Warcraft Logs matching — " + season + "\nNo fights have been evaluated.";
        }
        StringBuilder message = new StringBuilder("M+ / Warcraft Logs matching — ")
                .append(season).append('\n');
        counts.forEach(count -> message.append("• ").append(count.status()).append(": ")
                .append(count.count()).append('\n'));
        return message.append("UNMATCHED and AMBIGUOUS are coverage states, not zero-value combat runs.")
                .toString();
    }

    private boolean isClaimedByAnotherFight(MatchDecision decision, LogFight fight) {
        return decision.status() == MatchStatus.MATCHED
                && correlationRepository.hasDifferentMatchedFight(
                        decision.run().runId(), fight.reportCode(), fight.fightId()
                );
    }

    private static String formatCoverage(String profileName, String season, Coverage coverage) {
        if (coverage.observedRuns() == 0) {
            return "M+ log coverage — " + profileName + " — " + season
                    + "\nNo observed Raider.IO runs are available.";
        }
        BigDecimal percent = BigDecimal.valueOf(coverage.matchedRuns() * 100L)
                .divide(BigDecimal.valueOf(coverage.observedRuns()), 1, RoundingMode.HALF_UP);
        return "M+ log coverage — " + profileName + " — " + season + '\n'
                + coverage.matchedRuns() + " of " + coverage.observedRuns() + " observed runs ("
                + percent.toPlainString() + "%) have one deterministic public-log match.\n"
                + "Missing/private/ambiguous logs are unavailable, never zero-valued metrics.";
    }
}
