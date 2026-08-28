package com.blackbox.wow.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MPlusObservation(
        String name,
        String realm,
        String region,
        String season,
        BigDecimal itemLevel,
        BigDecimal scoreAll,
        BigDecimal scoreDps,
        BigDecimal scoreHealer,
        BigDecimal scoreTank,
        Instant crawledAt,
        List<RunSummary> runs,
        List<RunSummary> weeklyRuns,
        List<RunSummary> previousWeeklyRuns,
        boolean previousWeekAvailable
) {
    public record RunSummary(
            long raiderIoRunId,
            String dungeonName,
            String dungeonShortName,
            Integer mapChallengeModeId,
            int mythicLevel,
            Instant completedAt,
            long clearTimeMs,
            long parTimeMs,
            int keystoneUpgrades,
            BigDecimal score,
            boolean recent,
            boolean best,
            boolean weekly
    ) {
    }

    public record RunDetails(List<Member> members, List<Modifier> modifiers) {
    }

    public record Member(
            String region,
            String realm,
            String characterName,
            String className,
            String specName,
            String role
    ) {
    }

    public record Modifier(int id, String name, String slug) {
    }
}
