package com.blackbox.wow.repository;

import com.blackbox.wow.client.MPlusObservation;
import com.blackbox.wow.client.MPlusStaticData;
import com.blackbox.wow.helper.MPlusResetCalendar;
import com.blackbox.wow.helper.VaultSlotCalculator;
import com.blackbox.wow.helper.VaultSlotCalculator.VaultSlots;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static com.blackbox.wow.helper.JdbcTimestampMapper.toUtcOffset;

@Repository
public class MPlusDungeonVaultRepository {

    private static final String PARAM_PROFILE_ID = "profileId";
    private static final String PARAM_SEASON = "season";
    private static final String PARAM_PERIOD_START = "periodStart";
    private static final String REGION = "region";
    private static final String REALM = "realm";
    private static final String PARAM_CHARACTER_NAME = "characterName";
    private static final String UPSERT_VAULT_SNAPSHOT = """
            INSERT INTO mplus_weekly_vault_snapshot (
                profile_id, season_key, reset_period_start, region, realm,
                character_name, run_count, slot_one_level, slot_four_level,
                slot_eight_level, finalized, recovery_source, captured_at, finalized_at
            ) VALUES (
                :profileId, :season, :periodStart, :region, :realm,
                :characterName, :runCount, :slotOne, :slotFour,
                :slotEight, :finalized, :source, :capturedAt, :finalizedAt
            )
            ON CONFLICT (profile_id, season_key, reset_period_start) DO UPDATE SET
                run_count = GREATEST(
                    mplus_weekly_vault_snapshot.run_count,
                    EXCLUDED.run_count
                ),
                slot_one_level = GREATEST(
                    mplus_weekly_vault_snapshot.slot_one_level,
                    EXCLUDED.slot_one_level
                ),
                slot_four_level = GREATEST(
                    mplus_weekly_vault_snapshot.slot_four_level,
                    EXCLUDED.slot_four_level
                ),
                slot_eight_level = GREATEST(
                    mplus_weekly_vault_snapshot.slot_eight_level,
                    EXCLUDED.slot_eight_level
                ),
                finalized = mplus_weekly_vault_snapshot.finalized OR EXCLUDED.finalized,
                recovery_source = CASE WHEN EXCLUDED.finalized THEN EXCLUDED.recovery_source
                                       ELSE mplus_weekly_vault_snapshot.recovery_source END,
                captured_at = EXCLUDED.captured_at,
                finalized_at = COALESCE(
                    mplus_weekly_vault_snapshot.finalized_at,
                    EXCLUDED.finalized_at
                )
            """;

    private final JdbcClient jdbc;
    private final MPlusResetCalendar resetCalendar;

    public MPlusDungeonVaultRepository(JdbcClient jdbc, MPlusProgressProperties progressProperties) {
        this.jdbc = jdbc;
        this.resetCalendar = new MPlusResetCalendar(progressProperties);
    }

    @Transactional
    public void saveStaticData(MPlusStaticData staticData, Instant refreshedAt) {
        for (MPlusStaticData.Season season : staticData.seasons()) {
            for (MPlusStaticData.Dungeon dungeon : season.dungeons()) {
                saveDungeon(season.key(), dungeon, refreshedAt);
            }
        }
    }

    public void saveCurrentVaultSnapshot(
            TrackedPlayer player,
            MPlusObservation observation,
            Instant capturedAt
    ) {
        Instant currentPeriod = resetCalendar.periodStart(capturedAt);
        saveVaultSnapshot(
                player,
                observation,
                currentPeriod,
                runLevels(observation.weeklyRuns()),
                false,
                "CURRENT",
                capturedAt
        );
    }

    static List<Integer> runLevels(List<MPlusObservation.RunSummary> runs) {
        return runs.stream()
                .map(MPlusObservation.RunSummary::mythicLevel)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    public List<DungeonCoverage> dungeonCoverage(long profileId, String season) {
        return jdbc.sql("""
                        SELECT dungeon.dungeon_id, dungeon.challenge_mode_id,
                               dungeon.dungeon_name, dungeon.dungeon_short_name,
                               MAX(run.mythic_level) FILTER (WHERE run.timed) AS highest_timed_level,
                               MAX(run.score) AS best_observed_score
                        FROM mplus_season_dungeon dungeon
                        LEFT JOIN (
                            mplus_observed_run run
                            JOIN mplus_observed_run_profile run_profile
                              ON run_profile.run_id = run.id
                             AND run_profile.profile_id = :profileId
                        ) ON run.season_key = dungeon.season_key
                           AND (
                               run.map_challenge_mode_id = dungeon.challenge_mode_id
                               OR (run.map_challenge_mode_id IS NULL
                                   AND LOWER(run.dungeon_short_name) = LOWER(dungeon.dungeon_short_name))
                           )
                        WHERE dungeon.season_key = :season
                        GROUP BY dungeon.dungeon_id, dungeon.challenge_mode_id,
                                 dungeon.dungeon_name, dungeon.dungeon_short_name
                        ORDER BY dungeon.dungeon_name
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_SEASON, season)
                .query((resultSet, ignoredRowNumber) -> new DungeonCoverage(
                        resultSet.getInt("dungeon_id"),
                        resultSet.getInt("challenge_mode_id"),
                        resultSet.getString("dungeon_name"),
                        resultSet.getString("dungeon_short_name"),
                        nullableInteger(resultSet.getObject("highest_timed_level")),
                        resultSet.getBigDecimal("best_observed_score")
                ))
                .list();
    }

    private void saveDungeon(String season, MPlusStaticData.Dungeon dungeon, Instant refreshedAt) {
        jdbc.sql("""
                        INSERT INTO mplus_season_dungeon (
                            season_key, dungeon_id, challenge_mode_id, dungeon_slug,
                            dungeon_name, dungeon_short_name, keystone_timer_seconds, refreshed_at
                        ) VALUES (
                            :season, :dungeonId, :challengeModeId, :slug,
                            :name, :shortName, :timer, :refreshedAt
                        )
                        ON CONFLICT (season_key, dungeon_id) DO UPDATE SET
                            challenge_mode_id = EXCLUDED.challenge_mode_id,
                            dungeon_slug = EXCLUDED.dungeon_slug,
                            dungeon_name = EXCLUDED.dungeon_name,
                            dungeon_short_name = EXCLUDED.dungeon_short_name,
                            keystone_timer_seconds = EXCLUDED.keystone_timer_seconds,
                            refreshed_at = EXCLUDED.refreshed_at
                        """)
                .param(PARAM_SEASON, season)
                .param("dungeonId", dungeon.id())
                .param("challengeModeId", dungeon.challengeModeId())
                .param("slug", dungeon.slug())
                .param("name", dungeon.name())
                .param("shortName", dungeon.shortName())
                .param("timer", dungeon.keystoneTimerSeconds())
                .param("refreshedAt", toUtcOffset(refreshedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private void saveVaultSnapshot(
            TrackedPlayer player,
            MPlusObservation observation,
            Instant periodStart,
            List<Integer> runLevels,
            boolean finalized,
            String source,
            Instant capturedAt
    ) {
        VaultSlots slots = VaultSlotCalculator.calculate(runLevels);
        jdbc.sql(UPSERT_VAULT_SNAPSHOT)
                .param(PARAM_PROFILE_ID, player.profileId())
                .param(PARAM_SEASON, observation.season())
                .param(PARAM_PERIOD_START, toUtcOffset(periodStart), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(REGION, observation.region())
                .param(REALM, observation.realm())
                .param(PARAM_CHARACTER_NAME, observation.name())
                .param("runCount", slots.runCount())
                .param("slotOne", slots.slotOne(), Types.INTEGER)
                .param("slotFour", slots.slotFour(), Types.INTEGER)
                .param("slotEight", slots.slotEight(), Types.INTEGER)
                .param("finalized", finalized)
                .param("source", source)
                .param("capturedAt", toUtcOffset(capturedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("finalizedAt", finalized ? toUtcOffset(capturedAt) : null,
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static Integer nullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    public record DungeonCoverage(
            int dungeonId,
            int challengeModeId,
            String dungeonName,
            String shortName,
            Integer highestTimedLevel,
            BigDecimal bestObservedScore
    ) {
    }

}
