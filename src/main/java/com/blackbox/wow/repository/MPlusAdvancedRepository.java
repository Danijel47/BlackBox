package com.blackbox.wow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MPlusAdvancedRepository {

    private final JdbcClient jdbc;

    public MPlusAdvancedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModifierRunRow> modifierRunRows(long profileId, String season) {
        return jdbc.sql("""
                        SELECT run.id AS stored_run_id, run.dungeon_short_name,
                               run.mythic_level, run.clear_time_ms, run.par_time_ms, run.timed,
                               modifier.modifier_id, modifier.modifier_name, modifier.modifier_slug
                        FROM mplus_observed_run run
                        JOIN mplus_observed_run_profile run_profile ON run_profile.run_id = run.id
                        JOIN mplus_observed_run_modifier modifier ON modifier.run_id = run.id
                        WHERE run_profile.profile_id = :profileId
                          AND run.season_key = :season
                          AND run.clear_time_ms > 0
                          AND run.par_time_ms > 0
                        ORDER BY run.id, modifier.modifier_id
                        """)
                .param("profileId", profileId)
                .param("season", season)
                .query((resultSet, ignoredRowNumber) -> new ModifierRunRow(
                        resultSet.getLong("stored_run_id"),
                        resultSet.getString("dungeon_short_name"),
                        resultSet.getInt("mythic_level"),
                        resultSet.getLong("clear_time_ms"),
                        resultSet.getLong("par_time_ms"),
                        resultSet.getBoolean("timed"),
                        resultSet.getInt("modifier_id"),
                        resultSet.getString("modifier_name"),
                        resultSet.getString("modifier_slug")
                ))
                .list();
    }

    public record ModifierRunRow(
            long storedRunId,
            String dungeon,
            int mythicLevel,
            long clearTimeMs,
            long parTimeMs,
            boolean timed,
            int modifierId,
            String modifierName,
            String modifierSlug
    ) {
    }
}
