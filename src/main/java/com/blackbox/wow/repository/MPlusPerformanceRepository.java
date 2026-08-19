package com.blackbox.wow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class MPlusPerformanceRepository {

    private final JdbcClient jdbc;

    public MPlusPerformanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ObservedRun> observedRuns(long profileId, String season) {
        return jdbc.sql("""
                        SELECT run.raider_io_run_id, run.dungeon_name, run.dungeon_short_name,
                               run.mythic_level, run.completed_at, run.clear_time_ms,
                               run.par_time_ms, run.num_keystone_upgrades, run.score, run.timed
                        FROM mplus_observed_run run
                        JOIN mplus_observed_run_profile run_profile ON run_profile.run_id = run.id
                        WHERE run_profile.profile_id = :profileId
                          AND run.season_key = :season
                        ORDER BY run.completed_at DESC, run.raider_io_run_id DESC
                        """)
                .param("profileId", profileId)
                .param("season", season)
                .query((resultSet, ignoredRowNumber) -> new ObservedRun(
                        resultSet.getLong("raider_io_run_id"),
                        resultSet.getString("dungeon_name"),
                        resultSet.getString("dungeon_short_name"),
                        resultSet.getInt("mythic_level"),
                        toInstant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                        resultSet.getLong("clear_time_ms"),
                        resultSet.getLong("par_time_ms"),
                        resultSet.getInt("num_keystone_upgrades"),
                        resultSet.getBigDecimal("score"),
                        resultSet.getBoolean("timed")
                ))
                .list();
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record ObservedRun(
            long raiderIoRunId,
            String dungeonName,
            String shortName,
            int mythicLevel,
            Instant completedAt,
            long clearTimeMs,
            long parTimeMs,
            int keystoneUpgrades,
            BigDecimal score,
            boolean timed
    ) {
        public long timeRemainingMs() {
            return parTimeMs - clearTimeMs;
        }

        public long overtimeMs() {
            return clearTimeMs - parTimeMs;
        }
    }
}
