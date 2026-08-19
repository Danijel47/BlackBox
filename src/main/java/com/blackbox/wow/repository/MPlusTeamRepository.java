package com.blackbox.wow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class MPlusTeamRepository {

    private final JdbcClient jdbc;

    public MPlusTeamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TeamRunMemberRow> teamRunRows(String season) {
        return jdbc.sql("""
                        SELECT run.id AS stored_run_id, run.raider_io_run_id,
                               run.dungeon_short_name, run.mythic_level, run.completed_at,
                               run.clear_time_ms, run.par_time_ms, run.timed,
                               member.matched_profile_id, profile.profile_name,
                               member.character_name, member.class_name, member.spec_name, member.role
                        FROM mplus_observed_run run
                        JOIN mplus_observed_run_member member ON member.run_id = run.id
                        LEFT JOIN player_profile profile ON profile.id = member.matched_profile_id
                        WHERE run.season_key = :season
                          AND run.clear_time_ms > 0
                          AND run.par_time_ms > 0
                        ORDER BY run.completed_at, run.id, member.id
                        """)
                .param("season", season)
                .query((resultSet, ignoredRowNumber) -> new TeamRunMemberRow(
                        resultSet.getLong("stored_run_id"),
                        resultSet.getLong("raider_io_run_id"),
                        resultSet.getString("dungeon_short_name"),
                        resultSet.getInt("mythic_level"),
                        toInstant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                        resultSet.getLong("clear_time_ms"),
                        resultSet.getLong("par_time_ms"),
                        resultSet.getBoolean("timed"),
                        nullableLong(resultSet.getObject("matched_profile_id")),
                        resultSet.getString("profile_name"),
                        resultSet.getString("character_name"),
                        resultSet.getString("class_name"),
                        resultSet.getString("spec_name"),
                        resultSet.getString("role")
                ))
                .list();
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    public record TeamRunMemberRow(
            long storedRunId,
            long raiderIoRunId,
            String dungeonShortName,
            int mythicLevel,
            Instant completedAt,
            long clearTimeMs,
            long parTimeMs,
            boolean timed,
            Long matchedProfileId,
            String profileName,
            String characterName,
            String className,
            String specName,
            String role
    ) {
    }
}
